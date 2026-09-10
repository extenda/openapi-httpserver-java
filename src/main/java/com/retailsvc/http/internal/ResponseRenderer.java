package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_RESET;

import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Writes a {@link Response} to an {@link HttpExchange}. */
public final class ResponseRenderer {

  /** Default smallest body worth gzipping: 1 KiB. */
  public static final long DEFAULT_MINIMUM_GZIP_BYTES = 1024;

  private static final String CONTENT_TYPE = "Content-Type";
  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String CONTENT_LENGTH = "Content-Length";
  private static final String VARY = "Vary";
  private static final String ACCEPT_ENCODING = "Accept-Encoding";
  private static final String GZIP = "gzip";
  private static final long UNKNOWN_LENGTH = -1;
  private static final long CHUNKED = 0;
  private static final String DEFAULT_JSON = "application/json";
  private static final String OCTET_STREAM = "application/octet-stream";

  private final Map<String, TypeMapper> mappers;
  private final long minimumGzipBytes;

  public ResponseRenderer(Map<String, TypeMapper> mappers, long minimumGzipBytes) {
    this.mappers = Map.copyOf(mappers);
    this.minimumGzipBytes = minimumGzipBytes;
  }

  public void render(HttpExchange exchange, Response response) throws IOException {
    try (exchange) {
      Headers headers = exchange.getResponseHeaders();
      response.headers().forEach(headers::add);

      Object body = response.body();
      int status = response.status();

      if (body == null) {
        renderEmpty(exchange, headers, status, response.contentType());
      } else if (body instanceof BodyWriter writer) {
        renderStream(exchange, headers, status, response.contentType(), writer);
      } else {
        renderBytes(exchange, headers, status, response.contentType(), body);
      }
    }
  }

  /**
   * Writes a bodiless response. Nothing can be coded here, but the response still has to say how a
   * body would have been coded: a length declared for a body the client will fetch separately would
   * describe the uncoded form, which is not what a coded {@code GET} would return.
   */
  private void renderEmpty(HttpExchange exchange, Headers headers, int status, String contentType)
      throws IOException {
    defaultContentType(headers, contentType);
    long declared = declaredLength(headers);
    if (shouldCompress(exchange, headers, status, contentType, declared) && declared >= 0) {
      headers.remove(CONTENT_LENGTH);
    }
    exchange.sendResponseHeaders(status, UNKNOWN_LENGTH);
  }

  private void renderStream(
      HttpExchange exchange, Headers headers, int status, String contentType, BodyWriter writer)
      throws IOException {
    defaultContentType(headers, contentType);
    long declared = writer instanceof BodyWriter.Sized sized ? sized.length() : UNKNOWN_LENGTH;
    boolean gzip = shouldCompress(exchange, headers, status, contentType, declared);
    if (gzip) {
      headers.set(CONTENT_ENCODING, GZIP);
    }
    exchange.sendResponseHeaders(status, gzip ? CHUNKED : Math.max(declared, CHUNKED));
    try (OutputStream out =
        gzip ? new GZIPOutputStream(exchange.getResponseBody()) : exchange.getResponseBody()) {
      writer.writeTo(out);
    }
  }

  /** Adds the response's own content type unless the handler already set one. */
  private static void defaultContentType(Headers headers, String contentType) {
    if (contentType != null && !headers.containsKey(CONTENT_TYPE)) {
      headers.add(CONTENT_TYPE, contentType);
    }
  }

  /**
   * Whether a body of {@code length} bytes should be gzipped, marking the response as varying by
   * {@code Accept-Encoding} whenever it could have been. A negative length means unknown, which
   * counts as over the threshold: measuring a stream to find out would defeat streaming it.
   */
  private boolean shouldCompress(
      HttpExchange exchange, Headers headers, int status, String contentType, long length) {
    if (headers.containsKey(CONTENT_ENCODING)
        || !ResponseCompression.isCompressible(contentType)
        || !bodyAllowed(status)) {
      return false;
    }
    addVary(headers);
    return (length < 0 || length >= minimumGzipBytes) && acceptsGzip(exchange);
  }

  /** The length a handler declared for a body it did not write, or -1 when absent or unreadable. */
  private static long declaredLength(Headers headers) {
    String declared = headers.getFirst(CONTENT_LENGTH);
    if (declared == null) {
      return UNKNOWN_LENGTH;
    }
    try {
      return Long.parseLong(declared.trim());
    } catch (NumberFormatException _) {
      return UNKNOWN_LENGTH;
    }
  }

  private void renderBytes(
      HttpExchange exchange, Headers headers, int status, String contentType, Object body)
      throws IOException {
    String fallback = body instanceof byte[] ? OCTET_STREAM : DEFAULT_JSON;
    String effectiveContentType = contentType != null ? contentType : fallback;
    byte[] bytes = body instanceof byte[] raw ? raw : serialize(body, effectiveContentType);
    defaultContentType(headers, effectiveContentType);
    byte[] payload = maybeCompress(exchange, headers, status, effectiveContentType, bytes);
    exchange.sendResponseHeaders(status, payload.length == 0 ? UNKNOWN_LENGTH : payload.length);
    if (payload.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    }
  }

  /** Gzips the body when it is worth it, leaving a payload gzip fails to shrink uncoded. */
  private byte[] maybeCompress(
      HttpExchange exchange, Headers headers, int status, String contentType, byte[] bytes)
      throws IOException {
    if (!shouldCompress(exchange, headers, status, contentType, bytes.length)) {
      return bytes;
    }
    byte[] gzipped = ResponseCompression.gzip(bytes);
    if (gzipped.length >= bytes.length) {
      return bytes;
    }
    headers.set(CONTENT_ENCODING, GZIP);
    return gzipped;
  }

  /** Statuses that carry no content cannot carry a content coding either. */
  private static boolean bodyAllowed(int status) {
    return status >= HTTP_OK
        && status != HTTP_NO_CONTENT
        && status != HTTP_RESET
        && status != HTTP_PARTIAL
        && status != HTTP_NOT_MODIFIED;
  }

  private static boolean acceptsGzip(HttpExchange exchange) {
    return AcceptEncodingHeader.acceptsGzip(exchange.getRequestHeaders().getFirst(ACCEPT_ENCODING));
  }

  /**
   * Marks the response as varying by {@code Accept-Encoding} so shared caches keep the coded and
   * uncoded forms apart. Announced whenever the body could have been coded, not only when it was,
   * and merged into one field line so a client reading a single value sees the whole list.
   */
  private static void addVary(Headers headers) {
    String existing = headers.getFirst(VARY);
    if (existing == null) {
      headers.set(VARY, ACCEPT_ENCODING);
      return;
    }
    for (String field : existing.split(",")) {
      String trimmed = field.trim();
      if ("*".equals(trimmed) || ACCEPT_ENCODING.equalsIgnoreCase(trimmed)) {
        return;
      }
    }
    headers.set(VARY, existing + ", " + ACCEPT_ENCODING);
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
