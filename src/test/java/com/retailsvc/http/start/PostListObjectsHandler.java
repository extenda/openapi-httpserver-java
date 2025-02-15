package com.retailsvc.http.start;

import com.retailsvc.http.openapi.model.GetRequestBody;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostListObjectsHandler implements HttpHandler, GetRequestBody {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    LOG.debug("POST /list/objects");

    byte[] bytes = getRequestBody(exchange);

    if (bytes.length == 0) {
      LOG.debug("No bytes available to read from the request body");
    } else {
      LOG.debug("Read {} bytes from the request body", bytes.length);
    }

    String requestBody = new String(bytes);
    LOG.debug("Request body: {}", requestBody);

    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, 0);
    }
  }
}
