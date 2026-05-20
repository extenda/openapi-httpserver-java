package com.retailsvc.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retailsvc.http.internal.gson.GsonJsonMapper;
import java.util.Objects;

/**
 * {@link TypeMapper} for {@code application/json} backed by Gson. The caller supplies a fully
 * configured {@link Gson}; this class never silently mutates it.
 *
 * <p>The no-argument constructor uses the library's default {@link Gson} — the same JSR-310-aware
 * instance the built-in auto-registration produces — making this a drop-in replacement for the
 * auto-registered mapper when callers want to wire it explicitly.
 *
 * <p>To extend the library default with extra type adapters or settings, use {@link
 * #gsonBuilder()}:
 *
 * <pre>{@code
 * Gson custom =
 *     new GsonTypeMapper()
 *         .gsonBuilder()
 *         .registerTypeAdapter(MyType.class, new MyTypeAdapter())
 *         .create();
 * new GsonTypeMapper(custom);
 * }</pre>
 */
public final class GsonTypeMapper implements TypedTypeMapper {

  private final GsonJsonMapper delegate;

  /** Creates a mapper backed by the library's default JSR-310-aware {@link Gson}. */
  public GsonTypeMapper() {
    this.delegate = new GsonJsonMapper();
  }

  /**
   * Creates a mapper backed by the supplied {@link Gson}.
   *
   * @throws NullPointerException if {@code gson} is null
   */
  public GsonTypeMapper(Gson gson) {
    this.delegate = new GsonJsonMapper(Objects.requireNonNull(gson, "gson must not be null"));
  }

  /**
   * Returns a {@link GsonBuilder} pre-populated with the wrapped {@link Gson}'s configuration, so
   * callers can derive a customized {@link Gson} from the library default (or from their own
   * starting point).
   */
  public GsonBuilder gsonBuilder() {
    return delegate.gson().newBuilder();
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return delegate.readFrom(body, contentTypeHeader);
  }

  @Override
  public <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type) {
    return delegate.readAs(body, contentTypeHeader, type);
  }

  @Override
  public byte[] writeTo(Object value) {
    return delegate.writeTo(value);
  }
}
