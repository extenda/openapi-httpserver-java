package com.retailsvc.http;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only per-request handle passed to {@link RequestHandler}. Carries the parsed body, path
 * parameters, query parameters, headers, and operation ID. Handlers consume a {@code Request} and
 * return a {@link Response}.
 */
public final class Request {

  private final HttpExchange exchange;
  private final byte[] body;
  private final Object parsed;
  private final String operationId;
  private final Map<String, String> pathParameters;
  private Map<String, String> queryParamCache;

  public Request(
      HttpExchange exchange,
      byte[] body,
      Object parsed,
      String operationId,
      Map<String, String> pathParameters) {
    this.exchange = exchange;
    this.body = body;
    this.parsed = parsed;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
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

  /** Value of the path parameter {@code name}, or {@code null} if absent. */
  public String pathParam(String name) {
    return pathParameters.get(name);
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
}
