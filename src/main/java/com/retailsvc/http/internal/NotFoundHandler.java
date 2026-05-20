package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Returns 404 with no body. Used for the framework's catch-all {@code /} context. */
public final class NotFoundHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1);
    }
  }
}
