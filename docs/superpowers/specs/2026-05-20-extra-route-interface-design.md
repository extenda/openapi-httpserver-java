# Extra-route interface, ExceptionHandler decoupling, and problem+json via registered TypeMapper

Date: 2026-05-20

## Problem

The library's stated goal is to wrap `com.sun.net.httpserver.HttpServer` behind transport-neutral abstractions (`Request`, `Response`, `RequestHandler`). Today three public API surfaces still leak the JDK types:

1. `OpenApiServer.Builder.extraRoute(String, com.sun.net.httpserver.HttpHandler)` — the registration point for routes that bypass OpenAPI (alive / health / spec endpoints).
2. `Handlers.aliveHandler()`, `healthHandler(...)`, `specHandler(...)`, `notFoundHandler()` — return `HttpHandler`.
3. `ExceptionHandler.handle(HttpExchange, Throwable)` — exposes `HttpExchange` to user code that defines a custom error mapper.

This couples the public API to the JDK server and blocks a future swap to a different transport (Netty, Helidon Níma, etc.).

Separately, `internal/ProblemDetailRenderer` hand-rolls JSON for RFC 7807 problem+json bodies, with bespoke escape logic for quotes, backslashes, control characters, etc. The comment justifies this with "Hand-rolled to avoid pulling in a JSON library", but a JSON `TypeMapper` is now mandatory on every `OpenApiServer` (user-supplied, or auto-detected via the Gson fallback), so the hand-rolled path is no longer justified — and it's an unmaintained second escaper that can drift from the real one.

## Goals

- Remove every `com.sun.net.httpserver` import from `com.retailsvc.http.*` (excluding `com.retailsvc.http.internal.*`).
- Express extra routes through the same `RequestHandler` / `Request` / `Response` triple already used for OpenAPI operations.
- Express exception mapping as `Throwable → Response`, rendered by the framework.
- Delete `ProblemDetailRenderer`; serialize problem+json bodies through the registered JSON `TypeMapper`.

## Non-goals

- Path templating for extra routes (still exact match).
- OpenAPI validation for extra routes (still bypassed).
- Body parsing through `TypeMapper` for extra routes (handlers see raw bytes via `Request.bytes()`).
- Migrating the `internal/*` filters and handlers off `HttpExchange` — they are the transport adapter and stay coupled by design.

## Design

### 1. `Request` gains `method()`

Add an `HttpMethod method` field and accessor to `com.retailsvc.http.Request`. The existing `com.retailsvc.http.spec.HttpMethod` enum is reused (already public, already covers GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS/TRACE/CONNECT, and is used by `Router` and `MethodNotAllowedException`).

Construction sites:

- `RequestPreparationFilter` — already parses `HttpMethod method = HttpMethod.parse(exchange.getRequestMethod())` before routing; pass it into the `Request` constructor.
- New `ExtraRouteAdapter` (see §3) — passes its own parsed method.

The 8-arg `Request` constructor grows to 9-arg. The existing `@SuppressWarnings("java:S107")` annotation already documents that this parameter list is intentionally flat at the adapter boundary; that justification still applies.

`withPrincipals` is updated to thread `method` through.

### 2. `extraRoute` accepts `RequestHandler`

```java
public Builder extraRoute(String path, RequestHandler handler) { ... }
```

The old `HttpHandler` overload is removed (not deprecated). Pre-1.0; only internal callers (the `Handlers.*` factories) and tests use it, and they all migrate in the same PR.

The `Builder.extras` field becomes `LinkedHashMap<String, RequestHandler>`.
`HandlerConfig.extras` follows.

### 3. `ExtraRouteAdapter` (internal)

New `internal/ExtraRouteAdapter implements HttpHandler`. Bridges between the JDK transport and the user's `RequestHandler` for an extra path. For each registered extra, `OpenApiServer` wires:

```
HttpContext extraCtx = httpServer.createContext(path);
extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
extraCtx.setHandler(new ExtraRouteAdapter(handler, bodyMappers, renderer));
```

On each request, `ExtraRouteAdapter.handle(exchange)`:

1. Reads body bytes (`exchange.getRequestBody().readAllBytes()`).
2. Parses the method via `HttpMethod.parse(exchange.getRequestMethod())`.
3. Constructs a `Request` with: body bytes, `parsed=null`, `bodyMapper=null`, `operationId=null`, `pathParameters=Map.of()`, `rawQuery`, `headerLookup`, `principals=Map.of()`, `method`.
4. Calls `handler.handle(request)`.
5. Renders the returned `Response` via the existing `ResponseRenderer` (the same renderer instance used for OpenAPI operations is reused — no behaviour drift between extras and operations).

Exceptions thrown by the handler propagate to the outer `ExceptionFilter` exactly like operation handlers do today, so the same `ExceptionHandler` is invoked.

`Response` features (streaming, byte body, JSON body, status-only, headers) all work for extras without code duplication because `ResponseRenderer` is shared.

### 4. `ExceptionHandler` returns a `Response`

```java
@FunctionalInterface
public interface ExceptionHandler {
  Response handle(Throwable t);
}
```

`ExceptionFilter` becomes:

```java
public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
  try {
    chain.doFilter(exchange);
  } catch (RuntimeException | IOException t) {
    Response response = handler.handle(t);
    renderer.render(exchange, response);
  }
}
```

`ExceptionFilter` is constructed with both the handler and the shared `ResponseRenderer`. (`renderer.render(exchange, response)` already exists for the normal path; reused here.)

`Handlers.defaultExceptionHandler(TypeMapper jsonMapper)` takes the registered JSON mapper and becomes:

```java
public static ExceptionHandler defaultExceptionHandler(TypeMapper jsonMapper) {
  Objects.requireNonNull(jsonMapper, "jsonMapper");
  return t -> switch (t) {
    case ValidationException ve -> Response.bytes(
        HTTP_BAD_REQUEST,
        jsonMapper.writeTo(ProblemDetail.forValidation(ve.error())),
        "application/problem+json");
    case NotFoundException _ -> Response.notFound();
    case MethodNotAllowedException mna -> Response.status(HTTP_BAD_METHOD)
        .withHeader("Allow", mna.allowed().stream()
            .map(Enum::name).collect(Collectors.joining(", ")));
    default -> {
      LOG.error("Unhandled exception in handler", t);
      yield Response.status(HTTP_INTERNAL_ERROR);
    }
  };
}
```

`OpenApiServer.Builder.build()` supplies the resolved JSON mapper when no custom `ExceptionHandler` is set (the JSON mapper has already been resolved at that point via `resolveBodyMappers`).

Rationale: the exception path runs before a `Request` is necessarily built (e.g. a malformed URI in `RequestPreparationFilter` itself), so the handler signature cannot take `Request` — the simplest pre-`Request` signature is the right one.

### 4a. `ProblemDetail` record replaces `ProblemDetailRenderer`

New `internal/ProblemDetail` record (or public if we want users to be able to return problem+json from handlers — TBD; start internal):

```java
record ProblemDetail(
    String type, String title, int status, String detail,
    String pointer, String keyword) {
  static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(
        "about:blank", "Bad Request", 400, e.message(), e.pointer(), e.keyword());
  }
}
```

Serialization runs through whichever JSON `TypeMapper` the server is configured with (Gson by default, or Jackson if the user registered one). Field order and `null` handling are whatever the mapper produces — Gson and Jackson both emit fields in declaration order and skip nulls when configured; field-presence is asserted in tests against the actual configured mapper rather than a hand-rolled string.

`ProblemDetailRenderer` is deleted along with its bespoke escape logic.

### 5. `Handlers.*` factories migrate

Return types change from `HttpHandler` to `RequestHandler`. The 405-on-non-GET/HEAD check, previously a `MethodLimitedHandler` wrapper, is inlined in each factory:

```java
public static RequestHandler aliveHandler() {
  return req -> switch (req.method()) {
    case GET, HEAD -> Response.empty();
    default -> Response.status(HTTP_BAD_METHOD)
        .withHeader("Allow", "GET, HEAD");
  };
}
```

`healthHandler(TypeMapper, Supplier<HealthOutcome>)` and `specHandler(String)` follow the same shape. For `specHandler`, `HEAD` returns `Response.bytes(200, new byte[0], contentType).withHeader("Content-Length", String.valueOf(bytes.length))` to preserve the existing HEAD-omits-body behaviour.

`Handlers.notFoundHandler()` is dropped from the public API and moved to `internal/NotFoundHandler` (the framework's catch-all `/` context is its only caller).

`MethodLimitedHandler` (internal) is deleted.

### 6. Public API surface after the change

`com.retailsvc.http.*` (excluding `internal.*`) contains zero references to `com.sun.net.httpserver`. Grep verification:

```
grep -rn "com\.sun\.net\.httpserver" src/main/java/com/retailsvc/http/ \
  | grep -v "/internal/"
```

returns no results.

## Migration steps

1. Add `method` to `Request`; update `RequestPreparationFilter` to pass it.
2. Introduce `ExtraRouteAdapter`; switch `OpenApiServer` extras wiring to it.
3. Change `Builder.extraRoute` signature; update `HandlerConfig` and tests.
4. Change `ExceptionHandler` signature; update `ExceptionFilter` to accept a `ResponseRenderer`; rewrite `Handlers.defaultExceptionHandler(TypeMapper)` and wire it from `Builder.build()`.
5. Add `internal/ProblemDetail`; delete `internal/ProblemDetailRenderer`.
6. Migrate `Handlers.aliveHandler/healthHandler/specHandler` to `RequestHandler` with inline 405 checks.
7. Move `Handlers.notFoundHandler` to `internal/NotFoundHandler`.
8. Delete `MethodLimitedHandler`.
9. Update tests: `OpenApiServerBuilderTest`, `ExtraHandlersIT`, `HandlersTest` (if present), exception-handler tests (problem+json wire-shape now asserted as parsed JSON, not byte-equality), integration tests.

## Test plan

- Existing integration tests for extra routes (`ExtraHandlersIT`) pass unchanged behaviourally (paths still return the same bytes/status).
- `OpenApiServerBuilderTest` covers the duplicate-path rule for the new signature.
- New unit test: `ExtraRouteAdapter` constructs a `Request` with `operationId=null`, empty `pathParams`, empty `principals`, correct method, raw query, and body bytes; invokes the user handler; renders the response.
- Default exception handler produces the same wire output for the four known exception classes as the current implementation (byte-for-byte for the problem+json case).
- Custom exception handlers: regression test that user-supplied `ExceptionHandler` returning `Response.of(418, body)` is rendered.

## Risks

- **Behavioural drift in exception rendering.** Today the default handler writes headers and body directly via hand-rolled JSON. Routing through `Response`/`ResponseRenderer` and the registered JSON mapper changes both the code path and the exact bytes of the problem+json document (different mappers may differ in whitespace, null-omission, or field order). Mitigation: assert wire shape by parsing the response with the same mapper and comparing field-by-field, not byte-by-byte. Document that the exact byte output of problem+json depends on the registered JSON mapper.
- **Pre-`Request` exceptions losing context.** The new `ExceptionHandler` signature has no `Request`. User code that wanted to log the request path on error must use logging in the handler itself. Acceptable: the default handler already does not use request context, and user custom handlers that need it can attach an `OpenApiServer.builder().interceptor(...)` to capture request info into an MDC.
- **`Request.method()` non-null for OpenAPI handlers.** Since the OpenAPI router already dispatches by method, this is redundant for those handlers but consistent and cheap.

## Out of scope

- Removing `HttpExchange` from `internal/*`. The internal package is the transport adapter and intentionally coupled to the JDK server.
- A swap-the-transport SPI. Even with this change shipped, swapping transports still requires reworking `internal/*` filters; the API surface, however, no longer blocks that work.
