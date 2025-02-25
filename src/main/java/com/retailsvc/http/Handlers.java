package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);

  private Handlers() {}

  public static HttpHandler notFoundHandler() {
    return exchange -> {
      try (exchange) {
        endRequest(exchange, HTTP_NOT_FOUND);
      }
    };
  }

  public static ExceptionHandler internalServerErrorHandler() {
    return (exchange, throwable) -> {
      try (exchange) {
        LOG.error("Error in handling request", throwable);
        endRequest(exchange, HTTP_INTERNAL_ERROR);
      }
    };
  }

  public static ExceptionHandler defaultExceptionHandler() {
    return (exchange, e) -> internalServerErrorHandler().handleException(exchange, e);
  }

  private static void endRequest(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, 0);
  }
}
