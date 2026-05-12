package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Wraps a delegate handler so it answers only GET and HEAD. Other methods produce 405 with {@code
 * Allow: GET, HEAD}.
 */
public final class MethodLimitedHandler implements HttpHandler {

  private static final String ALLOW = "GET, HEAD";

  private final HttpHandler delegate;

  public MethodLimitedHandler(HttpHandler delegate) {
    this.delegate = delegate;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method) || "HEAD".equals(method)) {
      delegate.handle(exchange);
      return;
    }
    try (exchange) {
      exchange.getResponseHeaders().add("Allow", ALLOW);
      exchange.sendResponseHeaders(HTTP_BAD_METHOD, -1);
    }
  }
}
