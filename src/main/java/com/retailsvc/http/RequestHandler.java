package com.retailsvc.http;

/**
 * Handles a single request identified by OpenAPI {@code operationId}. Registered on {@link
 * OpenApiServer.Builder#handlers(java.util.Map)} by operation ID.
 *
 * <p>Handlers are pure functions of the {@link Request}: they read inputs and return a {@link
 * Response} describing what should be sent. The framework renders the response after applying any
 * registered {@link ResponseDecorator}s. Handlers may throw any {@link RuntimeException}; the
 * configured {@link ExceptionHandler} renders it. Handlers that need to surface an {@code
 * IOException} should wrap it as {@link java.io.UncheckedIOException}.
 */
@FunctionalInterface
public interface RequestHandler {
  Response handle(Request request);
}
