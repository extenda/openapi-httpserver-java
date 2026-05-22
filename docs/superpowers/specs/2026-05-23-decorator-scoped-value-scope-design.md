# Decorator runs inside interceptor ScopedValue scope

## Problem

`README.md` ("Combining the two") promises that a `ResponseDecorator`
sees `ScopedValue` bindings established by a `RequestInterceptor`:

> Decorators run inside the interceptor's `ScopedValue` binding (the
> decorator transforms the `Response` returned by `next.proceed()`,
> which is still on the call stack), so `CORRELATION_ID.get()` /
> `TENANT_ID.get()` see the bound values.

The implementation contradicts this contract.
`DispatchHandler.handle` runs the entire interceptor chain to
completion, then loops decorators after the chain has unwound:

```java
Response response = invoke(0, request, handler);   // pops all
                                                    // ScopedValue
                                                    // frames
for (ResponseDecorator decorator : decorators) {
  response = decorator.decorate(request, response); // no bindings
}
```

A decorator that calls `CORRELATION_ID.get()` throws
`NoSuchElementException`.

## Fix

Move the decorator loop into the base case of `invoke()`, so
decorators run after `handler.handle(request)` and before the call
unwinds through the interceptor frames.

`DispatchHandler.handle`:

```java
public void handle(HttpExchange exchange) {
  Request request = CURRENT.get();
  RequestHandler handler = handlers.get(request.operationId());
  Response response = invoke(0, request, handler);
  exchange.setAttribute(RESPONSE_ATTR, response);
  renderer.render(exchange, response);
}

private Response invoke(int idx, Request req, RequestHandler h) {
  if (idx == interceptors.size()) {
    Response response = h.handle(req);
    for (ResponseDecorator d : decorators) {
      response = d.decorate(req, response);
    }
    return response;
  }
  return interceptors.get(idx)
      .around(req, () -> invoke(idx + 1, req, h));
}
```

## Consequences

- Decorators see all interceptor `ScopedValue` bindings. README
  contract honored.
- Interceptors observe the decorated `Response` on the way back up.
  An interceptor wrapping `next.proceed()` in `try`/log can record
  the final status, headers, and body produced by the handler +
  decorators. README already implies this.
- If a decorator throws, the exception propagates through the
  interceptor chain. Previously it skipped interceptors and went
  straight to `ExceptionFilter`. Interceptors that wrap
  `next.proceed()` in `try`/`catch` now observe decorator failures.
  Worth a release note.

## Out of scope

`AfterResponseHook` runs from `RequestPreparationFilter` after the
interceptor chain has unwound and therefore does not see interceptor
`ScopedValue` bindings. That is a separate gap with a different
fix (the hook would need to fire from inside the interceptor frame,
which changes the documented "interceptor wraps the handler"
contract). Users who need per-request context in an after-callback
can register a closure via `request.afterResponse(Runnable)` from
inside the interceptor — the runnable captures the resolved values.

## Tests

Add to `DispatchHandlerTest` (or create one):

1. **Decorator sees interceptor-bound `ScopedValue`.** Register an interceptor that binds a `ScopedValue<String>` and a decorator that reads it and stamps a header. Assert the header is present on the rendered response. Without the fix this throws `NoSuchElementException`.
2. **Interceptor observes decorated response.** Register an interceptor that captures the `Response` returned by `next.proceed()`. Register a decorator that adds a header. Assert the captured response carries the decorator-added header.
3. **Decorator failure is visible to interceptors.** Register an interceptor with `try`/`catch` around `next.proceed()` and a decorator that throws. Assert the interceptor's catch block ran.

## Files touched

- `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`
- `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`
  (extend existing or add)

## README

No change. The current wording in "Combining the two" is correct;
the implementation is being brought into compliance.
