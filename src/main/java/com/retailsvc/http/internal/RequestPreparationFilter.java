package com.retailsvc.http.internal;

import com.retailsvc.http.AfterResponseHook;
import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.MediaType;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.Parameter;
import com.retailsvc.http.spec.RequestBody;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.ValidationError;
import com.retailsvc.http.validate.Validator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter that reads the request body, resolves the OpenAPI operation, validates parameters and
 * body, and exposes the parsed {@link Request} via {@link DispatchHandler#CURRENT} for downstream
 * filters and the dispatch handler.
 */
public final class RequestPreparationFilter extends Filter {

  private static final Logger LOG = LoggerFactory.getLogger(RequestPreparationFilter.class);
  private static final String BODY_POINTER = "/body";

  private final Spec spec;
  private final Router router;
  private final Validator validator;
  private final Map<String, TypeMapper> bodyMappers;
  private final ExceptionHandler exceptionHandler;
  private final ResponseRenderer renderer;
  private final List<AfterResponseHook> afterHooks;

  /**
   * Creates a new request preparation filter.
   *
   * @param spec the parsed OpenAPI spec
   * @param router routes requests to their {@link Operation}
   * @param validator validates parameters and request bodies
   * @param bodyMappers media-type to {@link TypeMapper} registry for parsing request bodies
   * @param exceptionHandler handles exceptions thrown during request preparation and dispatch
   * @param renderer renders the {@link Response} produced by the exception handler
   * @param afterHooks hooks invoked after the response has been produced
   */
  @SuppressWarnings("java:S107")
  public RequestPreparationFilter(
      Spec spec,
      Router router,
      Validator validator,
      Map<String, TypeMapper> bodyMappers,
      ExceptionHandler exceptionHandler,
      ResponseRenderer renderer,
      List<AfterResponseHook> afterHooks) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.bodyMappers = Map.copyOf(bodyMappers);
    this.exceptionHandler = exceptionHandler;
    this.renderer = renderer;
    this.afterHooks = List.copyOf(afterHooks);
  }

  @Override
  public String description() {
    return "Request preparation";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    Request request;
    try {
      request = buildRequest(exchange);
    } catch (RuntimeException | IOException t) {
      Response response = exceptionHandler.handle(t);
      renderer.render(exchange, response);
      return;
    }

    try {
      ScopedValue.where(DispatchHandler.CURRENT, request)
          .call(
              () -> {
                try {
                  runInnerChain(exchange, chain);
                } finally {
                  fireAfterHooks(exchange, request);
                }
                return null;
              });
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private Request buildRequest(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();

    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    String path = stripBasePath(exchange.getRequestURI().getPath());

    var matchOpt = router.match(method, path);
    if (matchOpt.isEmpty()) {
      var allowed = router.allowedMethods(path);
      if (allowed.isEmpty()) {
        throw new NotFoundException(method + " " + path);
      }
      throw new MethodNotAllowedException(allowed);
    }
    Router.Match match = matchOpt.get();

    Operation op = match.operation();
    validateParameters(exchange, op, match.pathParameters());
    ParsedBody parsedBody = validateAndParseBody(exchange, op, body);

    var headers = exchange.getRequestHeaders();
    return new Request(
        body,
        parsedBody.value(),
        parsedBody.mapper(),
        op.operationId(),
        match.pathParameters(),
        exchange.getRequestURI().getRawQuery(),
        headers::getFirst,
        Map.of(),
        method);
  }

  private void runInnerChain(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      Response response = exceptionHandler.handle(t);
      renderer.render(exchange, response);
    }
  }

  private void fireAfterHooks(HttpExchange exchange, Request request) {
    Response response = resolveResponse(exchange);
    List<Runnable> snapshot = List.copyOf(request.afterHooks());

    for (AfterResponseHook hook : afterHooks) {
      try {
        hook.after(request, response);
      } catch (Exception t) {
        LOG.debug("after-response hook threw", t);
      }
    }
    for (Runnable runnable : snapshot) {
      try {
        runnable.run();
      } catch (Exception t) {
        LOG.debug("after-response runnable threw", t);
      }
    }
  }

  private static Response resolveResponse(HttpExchange exchange) {
    Object stashed = exchange.getAttribute(DispatchHandler.RESPONSE_ATTR);
    if (stashed instanceof Response r) {
      return r;
    }
    Headers headers = exchange.getResponseHeaders();
    String contentType = headers != null ? headers.getFirst("Content-Type") : null;
    Map<String, String> flat = new LinkedHashMap<>();
    if (headers != null) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        List<String> values = e.getValue();
        if (values != null && !values.isEmpty()) {
          flat.put(e.getKey(), values.get(0));
        }
      }
    }
    return new Response(exchange.getResponseCode(), null, contentType, flat);
  }

  private String stripBasePath(String path) {
    String base = spec.basePath();
    if (base == null || base.isEmpty() || base.equals("/")) {
      return path;
    }
    return path.startsWith(base) ? path.substring(base.length()) : path;
  }

  private void validateParameters(
      HttpExchange exchange, Operation op, Map<String, String> pathParams) {
    Map<String, String> query = null;
    for (Parameter p : op.parameters()) {
      String pointer = p.pointer();
      if (p.in() == Parameter.Location.QUERY && query == null) {
        query = parseQuery(exchange.getRequestURI().getQuery());
      }
      String value =
          switch (p.in()) {
            case PATH -> pathParams.get(p.name());
            case QUERY -> query.get(p.name());
            case HEADER -> exchange.getRequestHeaders().getFirst(p.name());
            case COOKIE -> null; // handled by future spec
          };
      if (value == null) {
        if (p.required()) {
          throw new ValidationException(
              new ValidationError(
                  pointer,
                  "required",
                  "required " + p.in().name().toLowerCase(Locale.ROOT) + " parameter is missing",
                  null));
        }
        continue;
      }
      validator.validate(ValueCoercion.coerce(value, p.schema(), pointer), p.schema(), pointer);
    }
  }

  /** Result of {@link #validateAndParseBody}: parsed payload plus the mapper that produced it. */
  private record ParsedBody(Object value, TypeMapper mapper) {
    static final ParsedBody EMPTY = new ParsedBody(null, null);
  }

  private ParsedBody validateAndParseBody(HttpExchange exchange, Operation op, byte[] body) {
    Optional<RequestBody> rb = op.requestBody();
    if (rb.isEmpty()) {
      return ParsedBody.EMPTY;
    }
    if (body.length == 0) {
      if (rb.get().required()) {
        throw new ValidationException(
            new ValidationError(BODY_POINTER, "required", "request body is required", null));
      }
      return ParsedBody.EMPTY;
    }
    String header = exchange.getRequestHeaders().getFirst("Content-Type");
    String mediaType = ContentTypeHeader.mediaType(header);
    MediaType mt = rb.get().content().get(mediaType);
    if (mt == null) {
      throw new ValidationException(
          new ValidationError(
              BODY_POINTER, "content-type", "unsupported content type: " + mediaType, null));
    }
    TypeMapper mapper = bodyMappers.get(mediaType);
    if (mapper == null) {
      throw new ValidationException(
          new ValidationError(
              BODY_POINTER, "content-type", "unsupported content type: " + mediaType, null));
    }
    Object parsed = mapper.readFrom(body, header);
    if (mediaType.equals("application/x-www-form-urlencoded") && parsed instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      parsed = FormBodyCoercion.coerce(typed, mt.schema());
    }
    validator.validate(parsed, mt.schema(), "");
    return new ParsedBody(parsed, mapper);
  }

  private static Map<String, String> parseQuery(String query) {
    if (query == null || query.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new HashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      out.putIfAbsent(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return out;
  }
}
