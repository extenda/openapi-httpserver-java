package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_OK;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Serves bytes loaded eagerly from a classpath resource. Content-Type is inferred from the file
 * extension. Throws {@link IllegalArgumentException} if the resource is missing.
 */
public final class ClasspathResourceHandler implements HttpHandler {

  private final byte[] bytes;
  private final String contentType;

  public ClasspathResourceHandler(String classpathResource) {
    try (InputStream in = ClasspathResourceHandler.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
      }
      this.bytes = in.readAllBytes();
    } catch (IOException io) {
      throw new IllegalArgumentException(
          "failed reading classpath resource: " + classpathResource, io);
    }
    this.contentType = contentTypeFor(classpathResource);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
      if ("HEAD".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(HTTP_OK, -1);
        return;
      }
      exchange.sendResponseHeaders(HTTP_OK, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static String contentTypeFor(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return "application/json";
    }
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
      return "application/yaml";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    return "application/octet-stream";
  }
}
