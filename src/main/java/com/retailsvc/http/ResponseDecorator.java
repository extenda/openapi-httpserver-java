package com.retailsvc.http;

/**
 * Mutates the {@link ResponseBuilder} returned by {@link Request#respond(int)} before the handler
 * receives it. Decorators run in registration order. They may set headers (including {@code
 * Content-Type}) but must not call a terminal method — terminals belong to the handler.
 *
 * <p>Decorators fire before the handler runs, so any headers the handler sets via the returned
 * builder override decorator-supplied values.
 */
@FunctionalInterface
public interface ResponseDecorator {
  void decorate(Request request, ResponseBuilder builder);
}
