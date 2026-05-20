package com.retailsvc.http;

/**
 * Wraps the {@link RequestHandler} invocation. Use for {@link ScopedValue} bindings, MDC, tracing,
 * authentication, or any other concern that should run uniformly around every handler.
 *
 * <p>Interceptors compose in registration order: the first registered runs outermost. Each
 * interceptor must call {@link Continuation#proceed()} and return its result (or a transformed
 * value). Exceptions propagate to the configured {@link ExceptionHandler}.
 */
@FunctionalInterface
public interface RequestInterceptor {

  /**
   * Runs around the next interceptor or handler in the chain.
   *
   * @param request the incoming request
   * @param next the continuation to invoke the rest of the chain
   * @return the response produced by the chain, possibly transformed
   */
  Response around(Request request, Continuation next);

  /** Continues the chain — calls the next interceptor, or the handler if this is the last one. */
  @FunctionalInterface
  interface Continuation {
    /**
     * Invokes the next link in the chain.
     *
     * @return the response from the downstream interceptor or handler
     */
    Response proceed();
  }
}
