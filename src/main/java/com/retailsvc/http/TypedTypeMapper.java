package com.retailsvc.http;

/**
 * Optional capability for {@link TypeMapper}s that can deserialise a request body directly into a
 * caller-supplied target type. The library uses this when handlers call {@link
 * Request#asPojo(Class)}; mappers that cannot meaningfully honour a target type (e.g. the built-in
 * form / text mappers) should not implement this interface.
 *
 * <p>Implementations should wrap any underlying {@link java.io.IOException} as a {@link
 * java.io.UncheckedIOException} — consistent with the surrounding {@link TypeMapper} contract.
 */
public interface TypedTypeMapper extends TypeMapper {

  /**
   * Deserialise {@code body} into an instance of {@code type}.
   *
   * @param body raw request body bytes
   * @param contentTypeHeader the full raw {@code Content-Type} header (for charset / params)
   * @param type the target type
   * @param <T> the target type
   * @return the deserialised instance
   */
  <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type);
}
