package com.retailsvc.http;

import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TypeMapper} for {@code application/json} backed by Jackson 3. The caller supplies a
 * fully-configured {@link ObjectMapper}; this class never adds modules or changes settings — the
 * mapper you pass is the mapper you get.
 *
 * <p>Implements {@link TypedTypeMapper}, so handlers can ask for a typed view of the body via
 * {@link Request#asPojo(Class)}.
 *
 * <p>Use this adapter for Jackson 3.x (group {@code tools.jackson.core}). For Jackson 2.x (group
 * {@code com.fasterxml.jackson.core}), use {@link Jackson2JsonTypeMapper} instead — the two majors
 * use disjoint package roots and can coexist on the same classpath.
 *
 * <p>Jackson is an <em>optional</em> Maven dependency of this library; consumers that use Jackson
 * must declare {@code tools.jackson.core:jackson-databind} themselves. Consumers that use Gson can
 * rely on the built-in {@code GsonJsonMapper} auto-fallback instead.
 *
 * <p>Typical wiring:
 *
 * <pre>{@code
 * OpenApiServer.builder()
 *     .spec(spec)
 *     .bodyMapper("application/json", new Jackson3JsonTypeMapper(myObjectMapper))
 *     .handlers(handlers)
 *     .build();
 * }</pre>
 *
 * <p>Jackson 3 made all I/O exceptions unchecked ({@code tools.jackson.core.JacksonException
 * extends RuntimeException}), so this adapter no longer needs to wrap them — read/write failures
 * propagate as-is to the caller.
 */
public final class Jackson3JsonTypeMapper implements TypedTypeMapper {

  private final ObjectMapper mapper;

  public Jackson3JsonTypeMapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return readAs(body, contentTypeHeader, Object.class);
  }

  @Override
  public <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type) {
    return mapper.readValue(body, type);
  }

  @Override
  public byte[] writeTo(Object value) {
    return mapper.writeValueAsBytes(value);
  }
}
