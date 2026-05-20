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

  Response handle(Throwable t);
}
