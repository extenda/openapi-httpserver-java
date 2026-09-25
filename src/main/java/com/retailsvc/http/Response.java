package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

import com.retailsvc.http.internal.BodyWriter;
import com.retailsvc.http.spec.HttpMethod;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

  /** Not defined by {@link java.net.HttpURLConnection}. */
  private static final int HTTP_UNPROCESSABLE_CONTENT = 422;

  public Response {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  // -- one-shot, no-body --

  /** {@code 204 No Content} with no body. Same as {@link #noContent()}. */
  public static Response empty() {
    return noContent();
  }

  /** Given status, no body. Prefer a named factory such as {@link #ok()} when one exists. */
  public static Response status(int status) {
    return new Response(status, null, null, Map.of());
  }

  /** {@code 200 OK} with no body. */
  public static Response ok() {
    return status(HTTP_OK);
  }

  /** {@code 204 No Content} with no body. */
  public static Response noContent() {
    return status(HTTP_NO_CONTENT);
  }

  /** {@code 304 Not Modified} with no body. */
  public static Response notModified() {
    return status(HTTP_NOT_MODIFIED);
  }

  /** {@code 400 Bad Request} with no body. */
  public static Response badRequest() {
    return status(HTTP_BAD_REQUEST);
  }

  /** {@code 401 Unauthorized} with no body. Add a {@code WWW-Authenticate} header as needed. */
  public static Response unauthorized() {
    return status(HTTP_UNAUTHORIZED);
  }

  /** {@code 403 Forbidden} with no body. */
  public static Response forbidden() {
    return status(HTTP_FORBIDDEN);
  }

  /**
   * {@code 405 Method Not Allowed} with no body and an {@code Allow} header listing {@code
   * allowed}.
   */
  public static Response methodNotAllowed(HttpMethod... allowed) {
    return methodNotAllowed(List.of(allowed));
  }

  /**
   * {@code 405 Method Not Allowed} with no body and an {@code Allow} header listing {@code allowed}
   * in {@link HttpMethod} declaration order.
   */
  public static Response methodNotAllowed(Collection<HttpMethod> allowed) {
    String allow =
        allowed.stream().sorted().distinct().map(Enum::name).collect(Collectors.joining(", "));
    return status(HTTP_BAD_METHOD).withHeader("Allow", allow);
  }

  /** {@code 409 Conflict} with no body. */
  public static Response conflict() {
    return status(HTTP_CONFLICT);
  }

  /** {@code 500 Internal Server Error} with no body. */
  public static Response internalServerError() {
    return status(HTTP_INTERNAL_ERROR);
  }

  // -- one-shot, JSON body --

  /** {@code 200 OK} with {@code body} serialised as JSON. */
  public static Response ok(Object body) {
    return new Response(HTTP_OK, body, null, Map.of());
  }

  /**
   * {@code 201 Created} with {@code body} serialised as JSON. Add a {@code Location} header for the
   * new resource via {@link #withLocation(String)}.
   */
  public static Response created(Object body) {
    return new Response(HTTP_CREATED, body, null, Map.of());
  }

  /** {@code 202 Accepted} with no body. Use for fire-and-forget async work. */
  public static Response accepted() {
    return new Response(HTTP_ACCEPTED, null, null, Map.of());
  }

  /** {@code 202 Accepted} with {@code body} serialised as JSON (typically a job/poll URL). */
  public static Response accepted(Object body) {
    return new Response(HTTP_ACCEPTED, body, null, Map.of());
  }

  /** {@code 400 Bad Request} with {@code body} serialised as JSON (e.g. a ProblemDetail). */
  public static Response badRequest(Object body) {
    return new Response(HTTP_BAD_REQUEST, body, null, Map.of());
  }

  /** {@code 403 Forbidden} with {@code body} serialised as JSON (e.g. a ProblemDetail). */
  public static Response forbidden(Object body) {
    return new Response(HTTP_FORBIDDEN, body, null, Map.of());
  }

  /** {@code 404 Not Found} with no body. */
  public static Response notFound() {
    return new Response(HTTP_NOT_FOUND, null, null, Map.of());
  }

  /** {@code 404 Not Found} with {@code body} serialised as JSON (e.g. a ProblemDetail). */
  public static Response notFound(Object body) {
    return new Response(HTTP_NOT_FOUND, body, null, Map.of());
  }

  /** {@code 409 Conflict} with {@code body} serialised as JSON (e.g. a ProblemDetail). */
  public static Response conflict(Object body) {
    return new Response(HTTP_CONFLICT, body, null, Map.of());
  }

  /**
   * {@code 422 Unprocessable Content} with {@code body} serialised as JSON (e.g. a ProblemDetail).
   * Use when the request is well-formed but breaks a business rule.
   */
  public static Response unprocessableContent(Object body) {
    return new Response(HTTP_UNPROCESSABLE_CONTENT, body, null, Map.of());
  }

  /** {@code 501 Not Implemented} with no body. */
  public static Response notImplemented() {
    return new Response(HTTP_NOT_IMPLEMENTED, null, null, Map.of());
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

  /** Sets the {@code Location} header, typically the URI of a newly {@link #created} resource. */
  public Response withLocation(String location) {
    return withHeader("Location", location);
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
