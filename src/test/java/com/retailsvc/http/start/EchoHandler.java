package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Echoes back the request body as a response body */
public class EchoHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] bytes = Request.bytes(exchange);

    if (bytes.length == 0) {
      LOG.debug("No bytes available to read from the request body");
    } else {
      LOG.debug("Read {} bytes from the request body", bytes.length);
    }

    String requestBody = new String(bytes);
    LOG.debug("Request body: {}", requestBody);

    try (var os = exchange.getResponseBody();
        exchange) {

      var responseHeaders = exchange.getResponseHeaders();
      responseHeaders.add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, requestBody.getBytes().length);
      os.write(requestBody.getBytes());
    }
  }
}
