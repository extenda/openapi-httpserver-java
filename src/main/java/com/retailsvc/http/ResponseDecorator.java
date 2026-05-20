package com.retailsvc.http;

/**
 * Transforms the {@link Response} returned by a handler before the server renders it. Decorators
 * run in registration order; the result of each is fed to the next. Use for cross-cutting headers
 * (correlation id, tenant id, server identifier) or any other uniform response shaping.
 *
 * <p>Because decorators run <em>after</em> the handler, decorator-supplied headers override
 * handler-supplied ones on conflict. If you need the opposite semantics, use {@link
 * Response#withHeaders(java.util.Map)} inside the handler instead.
 */
@FunctionalInterface
public interface ResponseDecorator {
  /**
   * Transforms the handler's response before rendering.
   *
   * @param request the originating request
   * @param response the response produced so far
   * @return the transformed response to pass on
   */
  Response decorate(Request request, Response response);
}
