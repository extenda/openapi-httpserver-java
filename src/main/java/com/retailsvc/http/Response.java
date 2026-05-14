package com.retailsvc.http;

import com.retailsvc.http.internal.BodyWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The value returned by every {@link RequestHandler}. Carries status, optional body, optional
 * content type, and headers. The framework renders it to the underlying {@code HttpExchange} after
 * any registered {@link ResponseDecorator}s have transformed it.
 *
 * <p>Body handling:
 *
 * <ul>
 *   <li>{@code null} body → no response body (status only).
 *   <li>{@code byte[]} body → written verbatim with the supplied content type.
 *   <li>Streaming body (via {@link #stream(int, String, StreamingBody)} / sized variant) → written
 *       incrementally.
 *   <li>Any other object → serialised by the {@link TypeMapper} registered for the response's
 *       content type (default {@code application/json}).
 * </ul>
 */
public record Response(int status, Object body, String contentType, Map<String, String> headers) {

  public Response {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  // -- one-shot, no-body --

  /** {@code 204 No Content} with no body. */
  public static Response empty() {
    return new Response(204, null, null, Map.of());
  }

  /** Given status, no body. Use for {@code 200 OK} no body, {@code 404}, {@code 405}, etc. */
  public static Response status(int status) {
    return new Response(status, null, null, Map.of());
  }

  // -- one-shot, JSON body --

  /** {@code 200 OK} with {@code body} serialised as JSON. */
  public static Response ok(Object body) {
    return new Response(200, body, null, Map.of());
  }

  /** {@code 202 Accepted} with no body. Use for fire-and-forget async work. */
  public static Response accepted() {
    return new Response(202, null, null, Map.of());
  }

  /** {@code 202 Accepted} with {@code body} serialised as JSON (typically a job/poll URL). */
  public static Response accepted(Object body) {
    return new Response(202, body, null, Map.of());
  }

  /** {@code status} with {@code body} serialised by the content-type's {@link TypeMapper}. */
  public static Response of(int status, Object body) {
    return new Response(status, body, null, Map.of());
  }

  // -- one-shot, text / raw bytes --

  /** {@code status} with {@code body} written as UTF-8 with {@code Content-Type: text/plain}. */
  public static Response text(int status, String body) {
    return new Response(
        status, body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8", Map.of());
  }

  /**
   * {@code status} with pre-serialised {@code bytes} written verbatim under {@code contentType}.
   */
  public static Response bytes(int status, byte[] bytes, String contentType) {
    return new Response(status, bytes, contentType, Map.of());
  }

  // -- streaming --

  /** Streaming response with unknown length (chunked transfer encoding). */
  public static Response stream(int status, String contentType, StreamingBody writer) {
    return new Response(status, new BodyWriter.Chunked(writer::writeTo), contentType, Map.of());
  }

  /** Streaming response with a known content length. */
  public static Response stream(int status, long length, String contentType, StreamingBody writer) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    return new Response(
        status, new BodyWriter.Sized(length, writer::writeTo), contentType, Map.of());
  }

  // -- non-destructive mutators --

  public Response withStatus(int newStatus) {
    return new Response(newStatus, body, contentType, headers);
  }

  public Response withContentType(String newContentType) {
    return new Response(status, body, newContentType, headers);
  }

  public Response withHeader(String name, String value) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>(headers);
    merged.put(name, value);
    return new Response(status, body, contentType, merged);
  }

  public Response withHeaders(Map<String, String> additional) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>(headers);
    merged.putAll(additional);
    return new Response(status, body, contentType, merged);
  }

  /** Writer signature for {@link #stream(int, String, StreamingBody)}. */
  @FunctionalInterface
  public interface StreamingBody {
    void writeTo(OutputStream out) throws IOException;
  }
}
