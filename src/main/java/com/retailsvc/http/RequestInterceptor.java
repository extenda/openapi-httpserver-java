package com.retailsvc.http;

import java.io.IOException;

/**
 * Wraps the {@link RequestHandler} invocation. Use for {@link ScopedValue} bindings, MDC, tracing,
 * authentication, or any other concern that should run uniformly around every handler.
 *
 * <p>Interceptors compose in registration order: the first registered runs outermost. Each
 * interceptor must call {@link Continuation#proceed()} to invoke the next interceptor (or the
 * handler, when last). Exceptions propagate to the library's standard {@code ExceptionFilter} and
 * {@code ExceptionHandler} pipeline.
 */
@FunctionalInterface
public interface RequestInterceptor {

  void around(Request request, Continuation next) throws IOException;

  /** Continues the chain — calls the next interceptor, or the handler if this is the last one. */
  @FunctionalInterface
  interface Continuation {
    void proceed() throws IOException;
  }
}
