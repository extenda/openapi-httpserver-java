package com.retailsvc.http.internal;

import com.retailsvc.http.ResponseBuilder;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultResponseBuilder implements ResponseBuilder {

  private static final String CONTENT_TYPE = "Content-Type";

  private final HttpExchange exchange;
  private final int status;
  private final Map<String, TypeMapper> mappers;
  private final Runnable onTerminated;
  private final Map<String, String> pendingHeaders = new LinkedHashMap<>();
  private boolean terminated;

  public DefaultResponseBuilder(
      HttpExchange exchange, int status, Map<String, TypeMapper> mappers, Runnable onTerminated) {
    this.exchange = exchange;
    this.status = status;
    this.mappers = mappers;
    this.onTerminated = onTerminated;
  }

  @Override
  public ResponseBuilder header(String name, String value) {
    checkNotTerminated();
    pendingHeaders.put(name, value);
    return this;
  }

  @Override
  public ResponseBuilder contentType(String contentType) {
    return header(CONTENT_TYPE, contentType);
  }

  @Override
  public void empty() throws IOException {
    terminate();
    try (exchange) {
      applyHeaders();
      exchange.sendResponseHeaders(status, -1);
    }
  }

  @Override
  public void bytes(byte[] body) throws IOException {
    terminate();
    try (exchange) {
      applyHeaders();
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      }
    }
  }

  @Override
  public void text(String body) throws IOException {
    pendingHeaders.putIfAbsent(CONTENT_TYPE, "text/plain; charset=UTF-8");
    bytes(body.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void json(Object body) throws IOException {
    this.body("application/json", body);
  }

  @Override
  public void body(String mediaType, Object value) throws IOException {
    TypeMapper mapper = mappers.get(mediaType.toLowerCase(java.util.Locale.ROOT));
    if (mapper == null) {
      throw new IllegalStateException("No TypeMapper registered for " + mediaType);
    }
    pendingHeaders.putIfAbsent(CONTENT_TYPE, mediaType);
    bytes(mapper.writeTo(value));
  }

  @Override
  public OutputStream stream() throws IOException {
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, 0);
    return closingExchange(exchange.getResponseBody());
  }

  @Override
  public OutputStream stream(long length) throws IOException {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, length);
    return closingExchange(exchange.getResponseBody());
  }

  /**
   * Wraps the response body so the underlying {@link HttpExchange} is closed once the caller closes
   * the stream.
   */
  private OutputStream closingExchange(OutputStream body) {
    return new FilterOutputStream(body) {
      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        try (exchange) {
          super.close();
        }
      }
    };
  }

  private void terminate() {
    checkNotTerminated();
    terminated = true;
    onTerminated.run();
  }

  private void checkNotTerminated() {
    if (terminated) {
      throw new IllegalStateException("Response already sent");
    }
  }

  private void applyHeaders() {
    pendingHeaders.forEach(exchange.getResponseHeaders()::add);
  }
}
