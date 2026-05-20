# After-Response Hook

**Status:** Proposed
**Date:** 2026-05-20
**Author:** thced

## Goal

Let library consumers register code that runs **after the HTTP response has been
written to the wire**, on the same virtual thread that handled the request, with
the library's request-scoped value still bound. Hook exceptions are swallowed so
they never affect the client (which has already received the response anyway).

Typical uses: telemetry flushes, audit log emission, post-commit notifications,
trace-span close, latency metrics with the final status code.

## API

### Global hook (boot-time)

```java
@FunctionalInterface
public interface AfterResponseHook {
  void after(Request request, Response response);
}
```

Registered on the builder:

```java
OpenApiServer.builder()
    .afterResponseHook((req, resp) -> log.info("{} {}", req.operationId(), resp.status()))
    .build();
```

Multiple hooks may be registered; they run in registration order.

### Per-request hook

Handlers (or interceptors) queue `Runnable`s on the current request:

```java
public final class Request {
  public void afterResponse(Runnable runnable) { /* appends to internal queue */ }
}
```

Multiple runnables may be queued; they run FIFO.

### Order

Global hooks fire first (registration order), then per-request runnables (FIFO).

### Exception policy

Every hook invocation is wrapped in `try { ... } catch (Throwable t) { LOG.debug(...) }`.
A throwing hook does not affect other hooks, the response (already sent), or the
exchange. Errors are not propagated.

## Execution model

After-hooks fire on the **request virtual thread**, inside the existing
`ScopedValue.where(DispatchHandler.CURRENT, request)` binding established by
`RequestPreparationFilter`. No re-binding, no thread hand-off.

Hooks fire after the bytes have been flushed to the client — i.e., after either
`ResponseRenderer.render(...)` returns in `DispatchHandler`, or
`ExceptionHandler.handle(...)` has written an error response.

## Structural change

To run hooks inside the existing scoped binding without re-binding, the
exception-handling responsibility moves from `ExceptionFilter` into
`RequestPreparationFilter`. `ExceptionFilter` is deleted.

Filter chain before:

```
ExceptionFilter → RequestPreparationFilter → SecurityFilter → DispatchHandler
```

Filter chain after:

```
RequestPreparationFilter → SecurityFilter → DispatchHandler
```

`RequestPreparationFilter` is the single owner of the exchange. Pseudo-code:

```java
try {
  // routing + parameter/body validation (may throw NotFound/MethodNotAllowed/Validation)
  Request request = build(...);
  ScopedValue.where(DispatchHandler.CURRENT, request).run(() -> {
    try {
      chain.doFilter(exchange);            // Security → Dispatch (renders or throws)
    } catch (Throwable t) {
      exceptionHandler.handle(exchange, t); // writes error response to exchange
    }
    // response is sent; scope still bound
    fireAfterHooks(request, exchange);
  });
} catch (Throwable t) {
  // pre-Request failure (404/405/validation): no Request, so no after-hooks
  exceptionHandler.handle(exchange, t);
}
```

The "extras" routes registered via `Builder.extraRoute(...)` keep their own
`ExceptionFilter` wrapper — these routes have no OpenAPI Request and no
after-hook semantics. `ExceptionFilter` the class is retained and used only for
extras contexts; it is no longer added to the OpenAPI context's filter chain.

## The `Response` object passed to hooks

On the success path, hooks receive the exact `Response` rendered by
`DispatchHandler` (after `ResponseDecorator`s).

On the error path, `ExceptionHandler` writes directly to the exchange and never
produces a `Response`. To keep the hook signature uniform, the framework
synthesises one after the error has been rendered:

```java
new Response(
    exchange.getResponseCode(),                  // 4xx/5xx from ExceptionHandler
    null,                                        // body already streamed; unavailable
    exchange.getResponseHeaders().getFirst("Content-Type"),
    flatten(exchange.getResponseHeaders()))      // first value per header name
```

Hooks must therefore treat `Response.body()` as **always `null` on error paths**.
Status and headers are accurate. This is documented on `AfterResponseHook`.

## Edge cases

**Streaming responses.** `Response.stream(...)` writes the body via a
`StreamingBody` callback inside `ResponseRenderer`. The hook fires after the
streaming callback returns, i.e., after the last byte has been written.

**Per-request queue when handler is missing.** `MissingOperationHandlerException`
is thrown by `DispatchHandler` after `Request` is built. The handler queue is
empty (handler never ran), so only global hooks fire. The error-path synthetic
`Response` is used.

**Pre-Request failures (404/405/validation).** No `Request` was built. Neither
global nor per-request hooks fire. Documented limitation.

**Hook throws.** Logged at DEBUG, swallowed. Next hook still runs.

**`afterResponse` called after hooks have started.** The runner snapshots the
queue before invoking the first per-request runnable. Calls to
`afterResponse(...)` from inside a running hook, or from a leaked `Request`
reference held after the response has been sent, are silently ignored. The
queue stays appendable (no clear/lock) — the snapshot semantics are what
guarantee deterministic execution. Documented; not enforced at runtime.

## Implementation outline

### `com.retailsvc.http.AfterResponseHook` (new public)

```java
package com.retailsvc.http;

@FunctionalInterface
public interface AfterResponseHook {
  void after(Request request, Response response);
}
```

### `com.retailsvc.http.Request` (modified)

Add an internal `List<Runnable>` field plus:

```java
public void afterResponse(Runnable runnable) {
  Objects.requireNonNull(runnable, "runnable must not be null");
  afterHooks.add(runnable);
}
```

A package-private getter (`List<Runnable> afterHooks()`) exposes the list to
`RequestPreparationFilter`. The list is initialised to an empty mutable
`ArrayList` in the constructor. The runner snapshots the list before iterating
so runnables added during hook execution are ignored.

The current `Request` constructor has seven parameters. Adding an eighth would
ripple through call sites; instead the field is initialised internally and
exposed only through `afterResponse(...)` and the package-private getter.

### `com.retailsvc.http.internal.RequestPreparationFilter` (modified)

- Constructor takes `ExceptionHandler` and `List<AfterResponseHook>` in addition
  to its current dependencies.
- `doFilter` restructured per the pseudo-code above.
- New private helper `fireAfterHooks(Request, HttpExchange)` builds the
  synthetic `Response` if needed, then invokes each hook inside a `try/catch`.

### `com.retailsvc.http.internal.ExceptionFilter`

- Deleted from the OpenAPI route's filter chain (folded into
  `RequestPreparationFilter`).
- For `extraRoute` contexts, `OpenApiServer` continues to install an
  `ExceptionFilter` (or an inline equivalent) so unhandled exceptions from
  extras still flow to the user's `ExceptionHandler`.

### `com.retailsvc.http.OpenApiServer` (modified)

- `HandlerConfig` gains `List<AfterResponseHook> afterHooks`.
- The OpenAPI context registration no longer adds `ExceptionFilter` first; it
  starts with the updated `RequestPreparationFilter` which is given the
  `ExceptionHandler` and the after-hook list.
- `Builder.afterResponseHook(AfterResponseHook)` appends to an `ArrayList`.

### `com.retailsvc.http.internal.ResponseRenderer` (no change expected)

`render` already runs synchronously on the request thread.

## Testing strategy

Unit tests (Surefire, `*Test.java`):

- `RequestTest`: `afterResponse(null)` throws NPE; multiple calls queue in order.
- `OpenApiServerBuilderTest` (new or extend existing): `afterResponseHook(null)`
  throws NPE; multiple hooks queue in order.

Integration tests (Failsafe, `*IT.java`) using the existing test server harness:

- **Success path:** handler returns 200; global hook + per-request hook both fire
  in order; both see the rendered status and operationId.
- **Per-request only:** no global hook registered; handler queues two runnables;
  both fire FIFO.
- **Handler throws:** handler queues a runnable then throws; runnable still
  fires (per-request queue is drained regardless); global hook sees synthetic
  Response with the error status.
- **Hook throws:** first hook throws, second hook still runs; response to client
  is unaffected.
- **Pre-Request failure:** request hits an unknown path; 404 returned; no hooks
  fire (assert global counter unchanged).
- **Scoped value visibility:** hook reads `DispatchHandler.CURRENT.get()` and
  gets the same `Request` instance the handler saw.
- **Thread identity:** hook captures `Thread.currentThread()` and the handler
  captures the same; assert equality (same virtual thread).

## Out of scope

- Async / off-thread hooks: explicitly not supported. Users wanting async
  behavior can submit to their own executor from inside a hook.
- Ordering across global hooks of different priorities. Insertion order only.
- Removing or de-registering hooks after `build()`.
- Hooks on `extraRoute` handlers.
- Mutating the response from a hook (impossible — bytes have been sent).
