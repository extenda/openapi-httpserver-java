package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetDataHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GetDataHandler.class);

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    LOG.debug("GET /data");

    try (exchange) {
      String response = "{\"id\":\"some-id\"}";
      byte[] bytes = response.getBytes();
      try (exchange;
          var os = exchange.getResponseBody()) {
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.add("content-type", "application/json");
        exchange.sendResponseHeaders(HTTP_OK, bytes.length);
        os.write(bytes);
      }
    }
  }
}
