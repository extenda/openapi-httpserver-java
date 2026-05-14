package com.retailsvc.http;

import com.retailsvc.http.internal.DefaultResponseBuilder;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-request handle passed to {@link RequestHandler}. Carries the parsed body, path
 * parameters, operation ID, and a fluent {@link ResponseBuilder} for writing the response.
 */
public final class Request {

  private final HttpExchange exchange;
  private final byte[] body;
  private final Object parsed;
  private final String operationId;
  private final Map<String, String> pathParameters;
  private final Map<String, TypeMapper> bodyMappers;
  private final List<ResponseDecorator> decorators;
  private Map<String, String> queryParamCache;
  private boolean responseSent;

  public Request(
      HttpExchange exchange,
      byte[] body,
      Object parsed,
      String operationId,
      Map<String, String> pathParameters,
      Map<String, TypeMapper> bodyMappers,
      List<ResponseDecorator> decorators) {
    this.exchange = exchange;
    this.body = body;
    this.parsed = parsed;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.bodyMappers = bodyMappers;
    this.decorators = List.copyOf(decorators);
  }

  public byte[] bytes() {
    return body;
  }

  public Object parsed() {
    return parsed;
  }

  public String operationId() {
    return operationId;
  }

  public Map<String, String> pathParams() {
    return pathParameters;
  }

  public String header(String name) {
    return exchange.getRequestHeaders().getFirst(name);
  }

  /**
   * Raw (percent-encoded) query string from the request URI, or {@code null} if the URI has no
   * query component.
   */
  public String rawQuery() {
    return exchange.getRequestURI().getRawQuery();
  }

  /**
   * Decoded query parameters keyed by name. Empty if the URI has no query. For repeated keys, the
   * first occurrence wins. Values are URL-decoded with UTF-8.
   */
  public Map<String, String> queryParams() {
    if (queryParamCache == null) {
      queryParamCache = parseQuery(rawQuery());
    }
    return queryParamCache;
  }

  /** First decoded value for {@code name}, or {@code null} if absent. */
  public String queryParam(String name) {
    return queryParams().get(name);
  }

  private static Map<String, String> parseQuery(String query) {
    if (query == null || query.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq < 0 ? pair : pair.substring(0, eq);
      String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
      out.putIfAbsent(
          URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
          URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
    }
    return out;
  }

  public ResponseBuilder respond(int status) {
    if (responseSent) {
      throw new IllegalStateException("Response already sent");
    }
    ResponseBuilder builder =
        new DefaultResponseBuilder(exchange, status, bodyMappers, () -> responseSent = true);
    for (ResponseDecorator decorator : decorators) {
      decorator.decorate(this, builder);
    }
    return builder;
  }
}
