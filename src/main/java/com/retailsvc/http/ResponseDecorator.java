package com.retailsvc.http;

/**
 * Transforms the {@link Response} returned by a handler before the framework renders it. Decorators
 * run in registration order; the result of each is fed to the next. Use for cross-cutting headers
 * (correlation id, tenant id, server identifier) or any other uniform response shaping.
 *
 * <p>Because decorators run <em>after</em> the handler, decorator-supplied headers override
 * handler-supplied ones on conflict. If you need the opposite semantics, use {@link
 * Response#withHeaders(java.util.Map)} inside the handler instead.
 *
 * <p>Decorators run inside the {@link RequestInterceptor} chain, so any {@link ScopedValue}
 * bindings established by interceptors are visible here.
 */
@FunctionalInterface
public interface ResponseDecorator {
  Response decorate(Request request, Response response);
}
