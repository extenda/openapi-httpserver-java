package com.retailsvc.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * {@link TypeMapper} for {@code application/json} backed by Jackson. The caller supplies a
 * fully-configured {@link ObjectMapper}; this class never adds modules or changes settings — the
 * mapper you pass is the mapper you get.
 *
 * <p>Implements {@link TypedTypeMapper}, so handlers can ask for a typed view of the body via
 * {@link Request#asPojo(Class)}.
 *
 * <p>Typical wiring:
 *
 * <pre>{@code
 * OpenApiServer.builder()
 *     .spec(spec)
 *     .bodyMapper("application/json", new Jackson2JsonTypeMapper(myObjectMapper))
 *     .handlers(handlers)
 *     .build();
 * }</pre>
 *
 * <p>Jackson is an <em>optional</em> Maven dependency of this library; consumers that use Jackson
 * must declare {@code jackson-databind} themselves. Consumers that use Gson can rely on the
 * built-in {@code GsonJsonMapper} auto-fallback instead.
 */
public final class Jackson2JsonTypeMapper implements TypedTypeMapper {

  private final ObjectMapper mapper;

  public Jackson2JsonTypeMapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return readAs(body, contentTypeHeader, Object.class);
  }

  @Override
  public <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public byte[] writeTo(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
