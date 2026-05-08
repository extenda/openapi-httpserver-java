package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ParamHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ParamHandler.class);

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    LOG.debug("GET /params");

    try (exchange) {
      // -1 = no response body. Passing 0 would trigger chunked transfer encoding with zero chunks,
      // which is technically non-conformant for an empty 200.
      exchange.sendResponseHeaders(HTTP_OK, -1);
    }
  }
}
