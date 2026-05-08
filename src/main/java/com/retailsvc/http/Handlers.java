package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.retailsvc.http.internal.ProblemDetailRenderer;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);

  private Handlers() {}

  public static ExceptionHandler defaultExceptionHandler() {
    return (exchange, t) -> {
      try (exchange) {
        switch (t) {
          case ValidationException ve -> {
            byte[] body = ProblemDetailRenderer.render(ve.error()).getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(HTTP_BAD_REQUEST, body.length);
            exchange.getResponseBody().write(body);
          }
          case NotFoundException nf -> exchange.sendResponseHeaders(HTTP_NOT_FOUND, 0);
          case MethodNotAllowedException mna -> {
            String allow = mna.allowed().stream().map(Enum::name).collect(Collectors.joining(", "));
            exchange.getResponseHeaders().add("Allow", allow);
            exchange.sendResponseHeaders(HTTP_BAD_METHOD, 0);
          }
          default -> {
            LOG.error("Unhandled exception in handler", t);
            exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, 0);
          }
        }
      } catch (IOException io) {
        LOG.error("Failed writing error response", io);
      }
    };
  }

  public static HttpHandler notFoundHandler() {
    return exchange -> {
      try (exchange) {
        exchange.sendResponseHeaders(HTTP_NOT_FOUND, 0);
      }
    };
  }
}
