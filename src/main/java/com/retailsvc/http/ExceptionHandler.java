package com.retailsvc.http;

/**
 * Maps a {@link Throwable} thrown anywhere in the request pipeline to a {@link Response}.
 *
 * <p>Runs outside any {@code ScopedValue} bindings established by filters or interceptors — scopes
 * are torn down as the exception unwinds. Context-aware error mapping (trace IDs, etc.) should be
 * done in a {@link RequestInterceptor} that wraps {@code next.proceed()} in try/catch.
 */
@FunctionalInterface
public interface ExceptionHandler {

  /**
   * Maps the throwable to the {@link Response} to render.
   *
   * @param t the exception thrown anywhere in the request pipeline (never null)
   * @return the response to write; library default maps {@code BadRequestException} to 4xx
   *     problem+json and anything else to 500 problem+json with the exception message redacted by
   *     default
   */
  Response handle(Throwable t);
}
