package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.internal.BodyWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The value returned by every {@link RequestHandler}. Carries status, optional body, optional
 * content type, and headers. The server renders it to the underlying {@code HttpExchange} after any
 * registered {@link ResponseDecorator}s have transformed it.
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
 *
 * @param status the HTTP status code to send back to the client (e.g. {@code 200}, {@code 404}).
 * @param body the response payload, or {@code null} for a status-only response. May be a {@code
 *     byte[]} for verbatim bytes, a {@link BodyWriter} for streaming, or any object that the
 *     configured {@link TypeMapper} for {@link #contentType()} can serialise.
 * @param contentType the {@code Content-Type} header value for the response, or {@code null} to
 *     default to {@code application/json} when a body is present.
 * @param headers additional response headers to emit. Never {@code null} after canonicalisation;
 *     the compact constructor defensively copies the supplied map (or substitutes {@link Map#of()}
 *     if {@code null}) so the resulting {@code Response} is effectively immutable.
 */
public record Response(int status, Object body, String contentType, Map<String, String> headers) {

  /**
   * Canonical constructor that normalises {@code headers}: a {@code null} map is replaced with an
   * empty immutable map and any non-null map is defensively copied so subsequent mutations of the
   * caller's map cannot leak into this {@code Response}.
   */
  public Response {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  // -- one-shot, no-body --

  /**
   * {@code 204 No Content} with no body.
   *
   * @return a new {@code Response} with status {@code 204} and no body, content type, or headers.
   */
  public static Response empty() {
    return new Response(HTTP_NO_CONTENT, null, null, Map.of());
  }

  /**
   * Given status, no body. Use for {@code 200 OK} no body, {@code 404}, {@code 405}, etc.
   *
   * @param status the HTTP status code to send.
   * @return a new {@code Response} with the supplied status and no body, content type, or headers.
   */
  public static Response status(int status) {
    return new Response(status, null, null, Map.of());
  }

  // -- one-shot, JSON body --

  /**
   * {@code 200 OK} with {@code body} serialised as JSON.
   *
   * @param body the payload to serialise via the {@link TypeMapper} registered for {@code
   *     application/json}.
   * @return a new {@code Response} with status {@code 200} and the supplied body.
   */
  public static Response ok(Object body) {
    return new Response(HTTP_OK, body, null, Map.of());
  }

  /**
   * {@code 201 Created} with {@code body} serialised as JSON. Add a {@code Location} header for the
   * new resource via {@link #withHeader(String, String) withHeader("Location", uri)}.
   *
   * @param body the representation of the newly-created resource, serialised via the {@link
   *     TypeMapper} registered for {@code application/json}.
   * @return a new {@code Response} with status {@code 201} and the supplied body.
   */
  public static Response created(Object body) {
    return new Response(HTTP_CREATED, body, null, Map.of());
  }

  /**
   * {@code 202 Accepted} with no body. Use for fire-and-forget async work.
   *
   * @return a new {@code Response} with status {@code 202} and no body, content type, or headers.
   */
  public static Response accepted() {
    return new Response(HTTP_ACCEPTED, null, null, Map.of());
  }

  /**
   * {@code 202 Accepted} with {@code body} serialised as JSON (typically a job/poll URL).
   *
   * @param body the payload describing where to poll for the async result, serialised via the
   *     {@link TypeMapper} registered for {@code application/json}.
   * @return a new {@code Response} with status {@code 202} and the supplied body.
   */
  public static Response accepted(Object body) {
    return new Response(HTTP_ACCEPTED, body, null, Map.of());
  }

  /**
   * {@code 404 Not Found} with no body.
   *
   * @return a new {@code Response} with status {@code 404} and no body, content type, or headers.
   */
  public static Response notFound() {
    return new Response(HTTP_NOT_FOUND, null, null, Map.of());
  }

  /**
   * {@code 404 Not Found} with {@code body} serialised as JSON (e.g. a ProblemDetail).
   *
   * @param body the payload to serialise, typically an RFC 7807 problem detail, via the {@link
   *     TypeMapper} registered for {@code application/json}.
   * @return a new {@code Response} with status {@code 404} and the supplied body.
   */
  public static Response notFound(Object body) {
    return new Response(HTTP_NOT_FOUND, body, null, Map.of());
  }

  /**
   * {@code 501 Not Implemented} with no body.
   *
   * @return a new {@code Response} with status {@code 501} and no body, content type, or headers.
   */
  public static Response notImplemented() {
    return new Response(HTTP_NOT_IMPLEMENTED, null, null, Map.of());
  }

  /**
   * {@code status} with {@code body} serialised by the content-type's {@link TypeMapper}.
   *
   * @param status the HTTP status code to send.
   * @param body the payload to serialise via the {@link TypeMapper} registered for the response's
   *     content type (defaults to {@code application/json}).
   * @return a new {@code Response} with the supplied status and body.
   */
  public static Response of(int status, Object body) {
    return new Response(status, body, null, Map.of());
  }

  // -- one-shot, text / raw bytes --

  /**
   * {@code status} with {@code body} written as UTF-8 with {@code Content-Type: text/plain}.
   *
   * @param status the HTTP status code to send.
   * @param body the text payload; encoded to bytes using {@link StandardCharsets#UTF_8}.
   * @return a new {@code Response} carrying the UTF-8 encoded bytes with {@code Content-Type:
   *     text/plain; charset=UTF-8}.
   */
  public static Response text(int status, String body) {
    return new Response(
        status, body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8", Map.of());
  }

  /**
   * {@code status} with pre-serialised {@code bytes} written verbatim under {@code contentType}.
   *
   * @param status the HTTP status code to send.
   * @param bytes the pre-serialised payload to write verbatim to the response body.
   * @param contentType the {@code Content-Type} header value to advertise for {@code bytes}.
   * @return a new {@code Response} carrying {@code bytes} as the body under {@code contentType}.
   */
  public static Response bytes(int status, byte[] bytes, String contentType) {
    return new Response(status, bytes, contentType, Map.of());
  }

  // -- streaming --

  /**
   * Streaming response with unknown length (chunked transfer encoding).
   *
   * @param status the HTTP status code to send.
   * @param contentType the {@code Content-Type} header value to advertise for the streamed body.
   * @param writer callback invoked with the response {@link OutputStream}; the body is written
   *     incrementally and flushed using chunked transfer encoding because the total length is not
   *     known up-front.
   * @return a new {@code Response} that will stream its body via {@code writer} when rendered.
   */
  public static Response stream(int status, String contentType, StreamingBody writer) {
    return new Response(status, new BodyWriter.Chunked(writer::writeTo), contentType, Map.of());
  }

  /**
   * Streaming response with a known content length.
   *
   * @param status the HTTP status code to send.
   * @param length the exact number of bytes that {@code writer} will produce; sent as {@code
   *     Content-Length}. Must be non-negative.
   * @param contentType the {@code Content-Type} header value to advertise for the streamed body.
   * @param writer callback invoked with the response {@link OutputStream}; the body is written
   *     incrementally but the total length is advertised up-front so chunked transfer encoding is
   *     not used.
   * @return a new {@code Response} that will stream exactly {@code length} bytes via {@code writer}
   *     when rendered.
   * @throws IllegalArgumentException if {@code length} is negative.
   */
  public static Response stream(int status, long length, String contentType, StreamingBody writer) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    return new Response(
        status, new BodyWriter.Sized(length, writer::writeTo), contentType, Map.of());
  }

  // -- non-destructive mutators --

  /**
   * Returns a copy of this response with the status code replaced.
   *
   * @param newStatus the HTTP status code to use in the returned {@code Response}.
   * @return a new {@code Response} identical to this one except for {@link #status()}.
   */
  public Response withStatus(int newStatus) {
    return new Response(newStatus, body, contentType, headers);
  }

  /**
   * Returns a copy of this response with the content type replaced.
   *
   * @param newContentType the {@code Content-Type} header value to use, or {@code null} to fall
   *     back to the library default ({@code application/json}) when serialising the body.
   * @return a new {@code Response} identical to this one except for {@link #contentType()}.
   */
  public Response withContentType(String newContentType) {
    return new Response(status, body, newContentType, headers);
  }

  /**
   * Returns a copy of this response with an additional (or replaced) header. Existing headers are
   * preserved; if {@code name} already exists its value is overwritten.
   *
   * @param name the header name to set.
   * @param value the header value to set.
   * @return a new {@code Response} with the merged headers.
   */
  public Response withHeader(String name, String value) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>(headers);
    merged.put(name, value);
    return new Response(status, body, contentType, merged);
  }

  /**
   * Returns a copy of this response with the supplied headers merged on top of the existing ones.
   * Entries in {@code additional} overwrite any existing header with the same name.
   *
   * @param additional the headers to merge into the returned {@code Response}.
   * @return a new {@code Response} with the merged headers.
   */
  public Response withHeaders(Map<String, String> additional) {
    LinkedHashMap<String, String> merged = new LinkedHashMap<>(headers);
    merged.putAll(additional);
    return new Response(status, body, contentType, merged);
  }

  /** Writer signature for {@link #stream(int, String, StreamingBody)}. */
  @FunctionalInterface
  public interface StreamingBody {
    /**
     * Writes the streamed response body to {@code out}. Implementations should write all bytes and
     * may flush as needed; the server closes the stream once this method returns.
     *
     * @param out the response output stream to write to.
     * @throws IOException if writing to {@code out} fails.
     */
    void writeTo(OutputStream out) throws IOException;
  }
}
