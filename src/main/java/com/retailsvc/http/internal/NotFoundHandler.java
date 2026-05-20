package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Returns 404 with no body. Used for the server's catch-all {@code /} context. */
public final class NotFoundHandler implements HttpHandler {

  /** Creates a new handler. */
  public NotFoundHandler() {
    // Stateless; nothing to initialise.
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1);
    }
  }
}
