# Extra (non-OpenAPI) handlers + builder

**Date:** 2026-05-12
**Status:** Design — ready for implementation plan

## Problem

`OpenApiServer` mounts handlers only by OpenAPI `operationId`. Everything outside the spec falls through to a catch-all `/` 404. Consumers need a way to expose operational endpoints that are not part of the API contract — for example `/alive` for liveness probes, or a spec-accessor route that serves the OpenAPI document itself at a stable URL.

These endpoints must bypass OpenAPI parameter / body validation entirely — they have no `operationId` and no schema.

## Goals

1. Allow callers to register extra `HttpHandler` instances at arbitrary URL paths, outside the OpenAPI spec.
2. Provide a small set of built-in helpers in `Handlers` for the most common cases (liveness, classpath-resource serving).
3. Replace the constructor sprawl on `OpenApiServer` with a builder, since this change adds a fifth parameter and more are likely in future waves.
4. Keep the existing public constructors for source/binary back-compat.

## Non-goals

- Routing by HTTP method on extra paths beyond what the helpers do internally (callers can compose their own `HttpHandler` if they need richer dispatch).
- Hot-mounting / hot-unmounting handlers after `build()` — registration is build-time only.
- Built-in readiness probes or metrics endpoints — out of scope; callers can supply their own `HttpHandler`.

## Design

### Public API — builder

```java
OpenApiServer server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(mapper)
    .handlers(operationHandlers)
    .exceptionHandler(exceptionHandler)   // optional, defaults to Handlers.defaultExceptionHandler()
    .port(8080)                            // optional, default 8080
    .addHandler("/alive", Handlers.aliveHandler())
    .addHandler("/schemas/v1/openapi.yaml",
                Handlers.specHandler("/schemas/v1/openapi.yaml"))
    .build();                              // throws IOException, starts the server
```

Rules:

- `OpenApiServer.builder()` returns a fresh `OpenApiServer.Builder`.
- `spec`, `jsonMapper`, `handlers` are required. `build()` throws `NullPointerException` if any is missing — matches current constructor behavior.
- `exceptionHandler` is optional. If null/unset, defaults to `Handlers.defaultExceptionHandler()` (current behavior).
- `port` defaults to `8080` (current behavior).
- `addHandler(String path, HttpHandler handler)` adds one entry. `path` is the URL path; `handler` is the user's `HttpHandler`. Both non-null.
- Calling `addHandler` twice with the same `path` → `IllegalStateException` from the second `addHandler` call (fail fast, not deferred to `build()`).
- An extra path equal to `spec.basePath()` is detected at `build()` time and rejected with `IllegalStateException` before `HttpServer.createContext` is called, with a clear message naming both the extra path and the OpenAPI base path.
- Existing two `OpenApiServer` constructors stay as thin delegators that call `builder()...build()`, for back-compat.

### Wiring inside `OpenApiServer`

For each `addHandler(path, handler)` entry, after the OpenAPI context is created and before the catch-all `/` 404 is registered:

```java
HttpContext extraCtx = httpServer.createContext(path);
extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
extraCtx.setHandler(handler);
```

Order of context creation inside `OpenApiServer`:

1. OpenAPI context at `spec.basePath()` (full validation pipeline).
2. Each `addHandler` path (extras), each with `ExceptionFilter` only.
3. Catch-all `/` → `Handlers.notFoundHandler()`.

`HttpServer` resolves contexts by longest-prefix match, so creation order does not affect correctness — but two contexts at the same path is undefined behavior. Duplicate extras are caught by `addHandler` itself (see API rules above). An extra path equal to `spec.basePath()` is caught at the start of `build()` and rejected with `IllegalStateException` before any `HttpServer.createContext` call.

Extra handlers do **not** receive `RequestPreparationFilter` (no body read, no validation, no `operationId` resolution) and are not dispatched through `DispatchHandler`. They are mounted directly. `ExceptionFilter` wraps them so any uncaught exception flows through the user-supplied `ExceptionHandler`, giving operational endpoints the same RFC-7807 error envelope as API routes.

### Built-in helpers in `Handlers`

```java
/** 204 No Content on GET/HEAD; 405 with Allow: GET, HEAD on other methods. */
public static HttpHandler aliveHandler();

/**
 * Serves a classpath resource. Content-Type is inferred from the file extension:
 *   .json          → application/json
 *   .yaml | .yml   → application/yaml
 *   .txt           → text/plain; charset=utf-8
 *   anything else  → application/octet-stream
 *
 * The resource is loaded eagerly when this method is called and cached in memory.
 * If the resource cannot be found on the classpath, this method throws
 * IllegalArgumentException — so misconfiguration fails at server build, not at
 * first request.
 *
 * Responds 200 on GET/HEAD; 405 with Allow: GET, HEAD on other methods.
 *
 * @param classpathResource absolute classpath path, e.g. "/schemas/v1/openapi.yaml"
 */
public static HttpHandler specHandler(String classpathResource);
```

Notes:

- `aliveHandler` sends `sendResponseHeaders(204, -1)` (no body).
- `specHandler` reads bytes via `Handlers.class.getResourceAsStream(classpathResource)`. Null → `IllegalArgumentException("classpath resource not found: " + classpathResource)`. Bytes are held in the closure for the handler's lifetime.
- Content-Length is set to the cached byte count; HEAD requests get headers only with the same Content-Length.
- No caching headers (no `ETag`, no `Cache-Control`). Callers who need them wrap their own handler.

### Testing

Unit tests (additions to `HandlersTest`):

- `aliveHandler` returns 204 with no body on GET.
- `aliveHandler` returns 204 with no body on HEAD.
- `aliveHandler` returns 405 with `Allow: GET, HEAD` on POST, PUT, DELETE.
- `specHandler` returns the resource bytes verbatim with inferred content type for `.json`, `.yaml`, `.yml`, `.txt`, and an unknown extension.
- `specHandler` throws `IllegalArgumentException` at construction when the classpath resource is missing.
- `specHandler` returns 405 with `Allow: GET, HEAD` on non-GET/HEAD methods.

Integration tests (additions, in a new `OpenApiServerBuilderIT` or extending `OpenApiServerIT`):

- Minimal builder smoke test: only required fields → server starts, OpenAPI route + at least one extra reachable.
- Extra handler bypasses validation: `addHandler("/alive", Handlers.aliveHandler())` is reachable and returns 204 even though `/alive` is not in the OpenAPI spec.
- Extra handler exception is delivered to `ExceptionHandler`: register a handler that throws `RuntimeException`, assert the configured `ExceptionHandler` writes the RFC-7807 envelope.
- Duplicate `addHandler` path → `IllegalStateException` thrown from the second `addHandler` call.
- Extra path equal to `spec.basePath()` → `IllegalStateException` from `build()` with a message naming both paths.
- Existing `OpenApiServer` constructors still work (back-compat smoke test).

## Out of scope

- HTTP method-aware routing for extras beyond what the helpers implement.
- Readiness probes, metrics, or any other built-in operational endpoint past `aliveHandler` and `specHandler`.
- Per-handler filter customization (e.g. attaching custom filters to one extra and not another).
- Dynamic registration after `build()`.
