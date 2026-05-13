package com.retailsvc.http.internal;

import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
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
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class RequestPreparationFilter extends Filter {

  private final Spec spec;
  private final Router router;
  private final Validator validator;
  private final Map<String, TypeMapper> bodyMappers;

  public RequestPreparationFilter(
      Spec spec, Router router, Validator validator, Map<String, TypeMapper> bodyMappers) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.bodyMappers = Map.copyOf(bodyMappers);
  }

  @Override
  public String description() {
    return "Request preparation";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
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
    Object parsedBody = validateAndParseBody(exchange, op, body);

    RequestContext ctx =
        new RequestContext(body, parsedBody, op.operationId(), match.pathParameters());

    runWithRequestContext(ctx, () -> chain.doFilter(exchange));
  }

  private static void runWithRequestContext(RequestContext ctx, IORunnable work)
      throws IOException {
    try {
      ScopedValue.where(LegacyRequestAccess.CONTEXT, ctx)
          .call(
              () -> {
                work.run();
                return null;
              });
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // Callable.call() throws Exception; nothing else can actually be thrown by the chain.
      throw new IOException(e);
    }
  }

  @FunctionalInterface
  private interface IORunnable {
    void run() throws IOException;
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

  private Object validateAndParseBody(HttpExchange exchange, Operation op, byte[] body) {
    Optional<RequestBody> rb = op.requestBody();
    if (rb.isEmpty()) {
      return null;
    }
    if (body.length == 0) {
      if (rb.get().required()) {
        throw new ValidationException(
            new ValidationError("/body", "required", "request body is required", null));
      }
      return null;
    }
    String header = exchange.getRequestHeaders().getFirst("Content-Type");
    String mediaType = ContentTypeHeader.mediaType(header);
    MediaType mt = rb.get().content().get(mediaType);
    if (mt == null) {
      throw new ValidationException(
          new ValidationError(
              "/body", "content-type", "unsupported content type: " + mediaType, null));
    }
    TypeMapper mapper = bodyMappers.get(mediaType);
    if (mapper == null) {
      throw new ValidationException(
          new ValidationError(
              "/body", "content-type", "unsupported content type: " + mediaType, null));
    }
    Object parsed = mapper.readFrom(body, header);
    if (mediaType.equals("application/x-www-form-urlencoded") && parsed instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      parsed = FormBodyCoercion.coerce(typed, mt.schema());
    }
    validator.validate(parsed, mt.schema(), "");
    return parsed;
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
