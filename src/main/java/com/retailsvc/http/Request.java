package com.retailsvc.http;

import com.retailsvc.http.internal.DefaultResponseBuilder;
import com.sun.net.httpserver.HttpExchange;
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
  private boolean responseSent;

  public Request(
      HttpExchange exchange,
      byte[] body,
      Object parsed,
      String operationId,
      Map<String, String> pathParameters,
      Map<String, TypeMapper> bodyMappers) {
    this.exchange = exchange;
    this.body = body;
    this.parsed = parsed;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.bodyMappers = bodyMappers;
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

  public ResponseBuilder respond(int status) {
    if (responseSent) {
      throw new IllegalStateException("Response already sent");
    }
    return new DefaultResponseBuilder(exchange, status, bodyMappers, () -> responseSent = true);
  }
}
