package com.retailsvc.http.internal;

import com.retailsvc.http.AfterResponseHook;
import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.Response;
import com.retailsvc.http.StreamingRequestHandler;
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
import java.io.PushbackInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RequestPreparationFilter extends Filter {

  private static final Logger LOG = LoggerFactory.getLogger(RequestPreparationFilter.class);
  private static final String BODY_POINTER = "/body";
  private static final String CONTENT_TYPE = "Content-Type";

  private final Spec spec;
  private final Router router;
  private final Validator validator;
  private final Map<String, TypeMapper> bodyMappers;
  private final ExceptionHandler exceptionHandler;
  private final ResponseRenderer renderer;
  private final List<AfterResponseHook> afterHooks;
  private final RequestBodyReader bodyReader;
  private final Map<String, StreamingRequestHandler> streamingHandlers;

  @SuppressWarnings("java:S107")
  public RequestPreparationFilter(
      Spec spec,
      Router router,
      Validator validator,
      Map<String, TypeMapper> bodyMappers,
      ExceptionHandler exceptionHandler,
      ResponseRenderer renderer,
      List<AfterResponseHook> afterHooks,
      RequestBodyReader bodyReader) {
    this(
        spec,
        router,
        validator,
        bodyMappers,
        exceptionHandler,
        renderer,
        afterHooks,
        bodyReader,
        Map.of());
  }

  /**
   * As the 8-argument constructor, but the operations in {@code streamingHandlers} get their body
   * as a stream: it is not read here beyond a one-byte peek, and not parsed or validated against
   * its schema.
   */
  @SuppressWarnings("java:S107")
  public RequestPreparationFilter(
      Spec spec,
      Router router,
      Validator validator,
      Map<String, TypeMapper> bodyMappers,
      ExceptionHandler exceptionHandler,
      ResponseRenderer renderer,
      List<AfterResponseHook> afterHooks,
      RequestBodyReader bodyReader,
      Map<String, StreamingRequestHandler> streamingHandlers) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.bodyMappers = Map.copyOf(bodyMappers);
    this.exceptionHandler = exceptionHandler;
    this.renderer = renderer;
    this.afterHooks = List.copyOf(afterHooks);
    this.bodyReader = bodyReader;
    this.streamingHandlers = Map.copyOf(streamingHandlers);
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
    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    String path = stripBasePath(exchange.getRequestURI().getPath());

    var matchOpt = router.match(method, path);
    StreamingRequestHandler streaming =
        matchOpt.map(m -> streamingHandlers.get(m.operation().operationId())).orElse(null);
    if (streaming != null) {
      return buildStreamingRequest(exchange, method, matchOpt.get(), streaming.decodeContent());
    }

    RequestBodyReader.Body decoded = bodyReader.read(exchange);
    byte[] body = decoded.bytes();

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

    return new Request(
            body,
            parsedBody.value(),
            parsedBody.mapper(),
            op.operationId(),
            match.pathParameters(),
            exchange.getRequestURI().getRawQuery(),
            decoded.headerLookup(),
            Map.of(),
            method)
        .withHeaders(decoded.headers());
  }

  /**
   * Builds the request for a streaming operation. Validation mirrors the buffered path in the same
   * order, short of parsing the body: a one-byte peek tells an empty body from a present one, which
   * decides between the {@code required} and {@code Content-Type} checks just as the body's length
   * does when buffered.
   */
  private Request buildStreamingRequest(
      HttpExchange exchange, HttpMethod method, Router.Match match, boolean decode)
      throws IOException {
    RequestBodyReader.Streamed streamed = bodyReader.stream(exchange, decode);
    Operation op = match.operation();
    validateParameters(exchange, op, match.pathParameters());
    RequestBody rb =
        op.requestBody()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "streaming operation declares no requestBody: " + op.operationId()));

    PushbackInputStream body = new PushbackInputStream(streamed.stream(), 1);
    int first = body.read();
    if (first == -1) {
      if (rb.required()) {
        throw requiredBodyMissing();
      }
    } else {
      body.unread(first);
      String header = exchange.getRequestHeaders().getFirst(CONTENT_TYPE);
      if (RequestContentType.match(header, rb.content()).isEmpty()) {
        throw unsupportedContentType(RequestContentType.mediaType(header, rb.content()));
      }
    }

    return Request.streaming(
            body,
            op.operationId(),
            match.pathParameters(),
            exchange.getRequestURI().getRawQuery(),
            streamed.headerLookup(),
            Map.of(),
            method)
        .withHeaders(streamed.headers());
  }

  private static ValidationException requiredBodyMissing() {
    return new ValidationException(
        new ValidationError(BODY_POINTER, "required", "request body is required", null));
  }

  private static ValidationException unsupportedContentType(String mediaType) {
    return new ValidationException(
        new ValidationError(
            BODY_POINTER, "content-type", "unsupported content type: " + mediaType, null));
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
    String contentType = headers != null ? headers.getFirst(CONTENT_TYPE) : null;
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
        query = QueryParams.parse(exchange.getRequestURI().getRawQuery());
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
        throw requiredBodyMissing();
      }
      return ParsedBody.EMPTY;
    }
    String header = exchange.getRequestHeaders().getFirst(CONTENT_TYPE);
    Map<String, MediaType> content = rb.get().content();
    RequestContentType.Match match =
        RequestContentType.match(header, content)
            .orElseThrow(
                () -> unsupportedContentType(RequestContentType.mediaType(header, content)));
    String mediaType = match.mediaType();
    MediaType mt = match.content();
    TypeMapper mapper = bodyMappers.get(mediaType);
    if (mapper == null) {
      mapper = bodyMappers.get(match.declared().toLowerCase(Locale.ROOT));
    }
    if (mapper == null) {
      throw unsupportedContentType(mediaType);
    }
    Object parsed;
    try {
      parsed = mapper.readFrom(body, header);
    } catch (RuntimeException e) {
      // Body could not be parsed (e.g. malformed JSON). Untrusted input -> 400, not 500.
      LOG.debug("Failed to parse request body", e);
      throw new ValidationException(
          new ValidationError(
              BODY_POINTER, "malformed", "request body is not valid " + mediaType, null));
    }
    if (mediaType.equals("application/x-www-form-urlencoded") && parsed instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      parsed = FormBodyCoercion.coerce(typed, mt.schema());
    }
    validator.validate(parsed, mt.schema(), "");
    return new ParsedBody(parsed, mapper);
  }
}
