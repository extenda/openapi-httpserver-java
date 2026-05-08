package com.retailsvc.http.internal;

import static com.retailsvc.http.Request.BODY;
import static com.retailsvc.http.Request.OPERATION_ID;
import static com.retailsvc.http.Request.PARSED_BODY;
import static com.retailsvc.http.Request.PATH_PARAMETERS;

import com.retailsvc.http.JsonMapper;
import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
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
  private final JsonMapper jsonMapper;

  public RequestPreparationFilter(
      Spec spec, Router router, Validator validator, JsonMapper jsonMapper) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String description() {
    return "Request preparation";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    exchange.setAttribute(BODY, body);

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
    exchange.setAttribute(OPERATION_ID, op.operationId());
    exchange.setAttribute(PATH_PARAMETERS, match.pathParameters());

    validateParameters(exchange, op, match.pathParameters());
    validateBody(exchange, op, body);

    chain.doFilter(exchange);
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
    Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
    for (Parameter p : op.parameters()) {
      String pointer = "/" + p.in().name().toLowerCase(Locale.ROOT) + "/" + p.name();
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
      validator.validate(value, p.schema(), pointer);
    }
  }

  private void validateBody(HttpExchange exchange, Operation op, byte[] body) {
    Optional<RequestBody> rb = op.requestBody();
    if (rb.isEmpty()) {
      return;
    }
    if (body.length == 0) {
      if (rb.get().required()) {
        throw new ValidationException(
            new ValidationError("/body", "required", "request body is required", null));
      }
      return;
    }
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null) {
      contentType = "application/json";
    }
    contentType = contentType.split(";", 2)[0].trim();
    MediaType mt = rb.get().content().get(contentType);
    if (mt == null) {
      throw new ValidationException(
          new ValidationError(
              "/body", "content-type", "unsupported content type: " + contentType, null));
    }
    Object parsed = jsonMapper.mapFrom(body);
    exchange.setAttribute(PARSED_BODY, parsed);
    validator.validate(parsed, mt.schema(), "");
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
