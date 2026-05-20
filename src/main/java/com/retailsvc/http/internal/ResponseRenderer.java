package com.retailsvc.http.internal;

import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/** Writes a {@link Response} to an {@link HttpExchange}. */
public final class ResponseRenderer {

  private static final String CONTENT_TYPE = "Content-Type";
  private static final String DEFAULT_JSON = "application/json";
  private static final String OCTET_STREAM = "application/octet-stream";

  private final Map<String, TypeMapper> mappers;

  public ResponseRenderer(Map<String, TypeMapper> mappers) {
    this.mappers = Map.copyOf(mappers);
  }

  public void render(HttpExchange exchange, Response response) throws IOException {
    try (exchange) {
      Headers headers = exchange.getResponseHeaders();
      response.headers().forEach(headers::add);

      Object body = response.body();
      int status = response.status();

      if (body == null) {
        exchange.sendResponseHeaders(status, -1);
      } else if (body instanceof BodyWriter writer) {
        renderStream(exchange, headers, status, response.contentType(), writer);
      } else {
        renderBytes(exchange, headers, status, response.contentType(), body);
      }
    }
  }

  private static void renderStream(
      HttpExchange exchange, Headers headers, int status, String contentType, BodyWriter writer)
      throws IOException {
    if (contentType != null && !headers.containsKey(CONTENT_TYPE)) {
      headers.add(CONTENT_TYPE, contentType);
    }
    long length = writer instanceof BodyWriter.Sized sized ? sized.length() : 0;
    exchange.sendResponseHeaders(status, length);
    try (OutputStream out = exchange.getResponseBody()) {
      writer.writeTo(out);
    }
  }

  private void renderBytes(
      HttpExchange exchange, Headers headers, int status, String contentType, Object body)
      throws IOException {
    byte[] bytes;
    String effectiveContentType;
    if (body instanceof byte[] raw) {
      bytes = raw;
      effectiveContentType = contentType != null ? contentType : OCTET_STREAM;
    } else {
      effectiveContentType = contentType != null ? contentType : DEFAULT_JSON;
      bytes = serialize(body, effectiveContentType);
    }
    if (!headers.containsKey(CONTENT_TYPE)) {
      headers.add(CONTENT_TYPE, effectiveContentType);
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private byte[] serialize(Object body, String contentType) {
    String mediaType = ContentTypeHeader.mediaType(contentType);
    TypeMapper mapper = mappers.get(mediaType.toLowerCase(Locale.ROOT));
    if (mapper == null) {
      throw new IllegalStateException("No TypeMapper registered for " + contentType);
    }
    return mapper.writeTo(body);
  }
}
