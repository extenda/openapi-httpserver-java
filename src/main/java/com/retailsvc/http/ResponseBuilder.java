package com.retailsvc.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Fluent response builder returned by {@link Request#respond(int)}. Each {@code Request} permits
 * exactly one terminal call ({@link #empty()}, {@link #bytes(byte[])}, {@link #text(String)},
 * {@link #json(Object)}, {@link #body(String, Object)}, {@link #stream()}, or {@link
 * #stream(long)}); calling any of them after the first throws {@link IllegalStateException}. {@link
 * #header(String, String)} / {@link #contentType(String)} must be called before the terminal.
 *
 * <p>Note: a {@code problem(...)} terminal is deferred — no public {@code ProblemDetail} type
 * exists yet; only the internal {@code ProblemDetailRenderer} is available.
 */
public interface ResponseBuilder {

  ResponseBuilder header(String name, String value);

  ResponseBuilder contentType(String contentType);

  void empty() throws IOException;

  void bytes(byte[] body) throws IOException;

  void text(String body) throws IOException;

  void json(Object body) throws IOException;

  void body(String mediaType, Object body) throws IOException;

  OutputStream stream() throws IOException;

  OutputStream stream(long length) throws IOException;
}
