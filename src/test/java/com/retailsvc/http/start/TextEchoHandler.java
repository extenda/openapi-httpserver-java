package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.internal.LegacyRequestAccess;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Echoes the parsed text/plain body back to the response. */
public class TextEchoHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String parsed = (String) LegacyRequestAccess.parsed();
    byte[] body = parsed.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(HTTP_OK, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
