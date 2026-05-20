package com.retailsvc.http;

/**
 * Callback invoked after an HTTP response has been written to the client. Runs on the same virtual
 * thread that handled the request, inside the library's request {@link ScopedValue} binding.
 *
 * <p>Hooks fire only when a {@link Request} was successfully constructed — i.e., routing and
 * parameter/body validation passed. Pre-request failures (404, 405, 400 validation) do not fire
 * hooks.
 *
 * <p>On the error path, {@link Response#body()} is always {@code null} because the body bytes have
 * already been sent. {@link Response#status()} and {@link Response#headers()} reflect what was
 * written to the wire.
 *
 * <p>Exceptions thrown by a hook are logged at DEBUG and swallowed; subsequent hooks still run.
 * Hooks compose in registration order.
 */
@FunctionalInterface
public interface AfterResponseHook {

  /**
   * Invoked after the response has been written to the client.
   *
   * <p>Any exception thrown by this method is logged at DEBUG and swallowed; it does not affect the
   * response (already sent) and does not prevent subsequent hooks from running.
   *
   * @param request the resolved {@link Request} that was handled; routing and parameter/body
   *     validation have already passed by the time this is called
   * @param response the {@link Response} that was written to the client. {@link Response#status()}
   *     and {@link Response#headers()} reflect what was sent on the wire; {@link Response#body()}
   *     may be {@code null} on streaming responses (and is always {@code null} on the error path,
   *     since the body bytes have already been emitted)
   */
  void after(Request request, Response response);
}
