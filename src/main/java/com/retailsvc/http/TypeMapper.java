package com.retailsvc.http;

/**
 * Reads and writes request/response bodies for a specific media type. Registered on {@link
 * OpenApiServer.Builder#bodyMapper(String, TypeMapper)} keyed by media type. The library ships
 * built-in mappers for {@code application/x-www-form-urlencoded}, {@code text/plain}, and {@code
 * text/html}; an {@code application/json} mapper must be supplied by the caller or auto-detected
 * via Gson on the classpath.
 */
public interface TypeMapper {

  /**
   * @param body raw request body bytes
   * @param contentTypeHeader the full raw {@code Content-Type} header, used for charset and other
   *     parameters (the JSON mapper ignores it)
   */
  Object readFrom(byte[] body, String contentTypeHeader);

  /** Serializes {@code value} to bytes suitable for writing as the response body. */
  byte[] writeTo(Object value);
}
