package com.retailsvc.http.internal;

import java.util.Map;

/**
 * Static accessors for per-request state populated by the request-preparation filter.
 *
 * <p>The state is bound to a {@link ScopedValue} for the duration of the request rather than stored
 * on the {@code HttpExchange}, because {@code HttpExchange.setAttribute} writes to a context-shared
 * map and would race across concurrent requests.
 *
 * <p>If a handler dispatches work to a non-structured executor (i.e. not a {@code
 * StructuredTaskScope}-managed thread), it must capture the values it needs before submitting — the
 * {@link ScopedValue} is not visible from arbitrary worker threads.
 *
 * <p>Temporary scaffolding bridging the static-accessor era to the per-request {@code Request}
 * handle. Removed in a follow-up task once the new handler API replaces all consumers.
 */
public final class LegacyRequestAccess {

  /** Bound by {@code RequestPreparationFilter} for the duration of each request. */
  public static final ScopedValue<RequestContext> CONTEXT = ScopedValue.newInstance();

  private LegacyRequestAccess() {}

  /**
   * Returns the full per-request context. Use this when a handler reads more than one field — every
   * call to {@link #bytes()}, {@link #parsed()}, {@link #operationId()}, or {@link #pathParams()}
   * walks the JDK's scope chain independently, so reading via {@code current()} once is cheaper.
   */
  public static RequestContext current() {
    return CONTEXT.get();
  }

  public static byte[] bytes() {
    return CONTEXT.get().body();
  }

  public static Object parsed() {
    return CONTEXT.get().parsedBody();
  }

  public static String operationId() {
    return CONTEXT.get().operationId();
  }

  public static Map<String, String> pathParams() {
    return CONTEXT.get().pathParameters();
  }
}
