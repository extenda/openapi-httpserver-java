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
        return;
      }

      if (body instanceof BodyWriter writer) {
        long length = writer instanceof BodyWriter.Sized sized ? sized.length() : 0;
        if (response.contentType() != null && !headers.containsKey(CONTENT_TYPE)) {
          headers.add(CONTENT_TYPE, response.contentType());
        }
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody()) {
          writer.writeTo(out);
        }
        return;
      }

      byte[] bytes;
      String contentType = response.contentType();
      if (body instanceof byte[] raw) {
        bytes = raw;
        if (contentType == null) {
          contentType = "application/octet-stream";
        }
      } else {
        if (contentType == null) {
          contentType = DEFAULT_JSON;
        }
        TypeMapper mapper = mappers.get(contentType.toLowerCase(Locale.ROOT));
        if (mapper == null) {
          throw new IllegalStateException("No TypeMapper registered for " + contentType);
        }
        bytes = mapper.writeTo(body);
      }
      if (!headers.containsKey(CONTENT_TYPE)) {
        headers.add(CONTENT_TYPE, contentType);
      }
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(bytes);
        }
      }
    }
  }
}
