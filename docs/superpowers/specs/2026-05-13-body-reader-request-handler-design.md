# BodyReader and RequestHandler

**Date:** 2026-05-13
**Status:** Approved, ready for plan

## Motivation

The library currently hardcodes body parsing inside `RequestPreparationFilter`: a
`switch` on media type dispatches to `FormUrlEncodedParser`, `TextPlainParser`,
or the user-supplied `JsonMapper`. Adding a new media type (XML, CBOR, etc.)
requires editing the filter. The `JsonMapper` name is also misleading once we
treat it as one mapper among several.

Handlers today receive a raw JDK `HttpExchange` and pull request data via
static accessors on `Request` backed by a `ScopedValue<RequestContext>`. The
ScopedValue exists only because `HttpHandler.handle(HttpExchange)` has nowhere
else to carry prepared data. That side channel is unnecessary if handlers
receive their own per-request object directly.

This change introduces two interfaces — `BodyReader` for pluggable parsing and
`RequestHandler` for handlers that receive a `Request` instead of an
`HttpExchange` — and folds response writing into `Request` as a fluent
gateway with one-shot and streaming terminals.

## Scope

In scope:

- `BodyReader` interface and per-media-type registration on the builder.
- Rename `JsonMapper` → the JSON `BodyReader`; default form and text readers
  wired automatically.
- New `RequestHandler` interface; `handlers(...)` builder method changed to
  `Map<String, RequestHandler>` (breaking).
- `Request` repurposed from a static-accessor utility into the per-request
  handle handlers receive. Read API mirrors today's `RequestContext`; adds a
  response gateway with one-shot and streaming terminals.
- Internal `RequestContext` record and public `Request.CONTEXT` `ScopedValue`
  removed.
- A required `jsonWriter(Object → byte[])` on the builder, used by
  `Request.respond(...).json(...)`. Generalising to `BodyWriter`s is a future
  change.

Out of scope:

- **Request streaming.** Handlers buffer the request body and validate it
  against the spec, as today. Streaming requests will be a follow-up; it needs
  a separate decision about how operations opt out of body validation.
- A general `BodyWriter` abstraction symmetric to `BodyReader`. Response
  serialization for non-JSON content types is the handler's responsibility via
  `respond(...).bytes(...)` / `.text(...)` / `.stream(...)`.

## Design

### `BodyReader`

```java
package com.retailsvc.http;

@FunctionalInterface
public interface BodyReader {
  Object readFrom(byte[] body, String contentTypeHeader);
}
```

`contentTypeHeader` is the full raw `Content-Type` header — required so form
and text readers can resolve `charset` and other parameters. JSON readers
ignore it.

`BodyReader` is schema-free. Today `FormUrlEncodedParser.parseAndCoerce` takes
the body `Schema` to coerce field values; that coercion moves into the
existing validator path that already coerces query/path/header parameters, so
the form reader becomes a plain `byte[]` → `Map<String,Object>` step.

### Builder registration

```java
OpenApiServer.builder()
    .spec(spec)
    .bodyReader("application/json", jsonReader)        // required (no default)
    .bodyReader("application/xml", xmlReader)          // optional extra
    .jsonWriter(jsonWriter)                            // required
    .handlers(Map.of("op", request -> { ... }))
    .build();
```

Defaults wired by the builder unless overridden:

- `application/x-www-form-urlencoded` → built-in form reader.
- `text/plain` → built-in text reader.
- `application/json` → **no default**; the user must supply one (mirrors the
  current contract that `jsonMapper(...)` is required).

Lookup: case-insensitive on the media-type subtype (existing
`ContentTypeHeader.mediaType` already lowercases). No wildcard matching
(`text/*`, `*/*`) — out of scope.

### `Request`

`com.retailsvc.http.Request` becomes the per-request handle. Concrete final
class (no interface — YAGNI; extract later if testability demands it).

```java
public final class Request {
  // read API — same data RequestContext exposes today
  public byte[] bytes();
  public Object parsed();
  public String operationId();
  public Map<String, String> pathParams();

  // small conveniences
  public String header(String name);
  public Map<String, String> queryParams();   // parsed lazily, cached

  // response gateway
  public ResponseBuilder respond(int status);
}
```

`ResponseBuilder` (fluent; exactly one terminal call per `Request`):

```java
public interface ResponseBuilder {
  ResponseBuilder header(String name, String value);
  ResponseBuilder contentType(String contentType);   // shorthand

  // one-shot terminals
  void empty();                       // sendResponseHeaders(status, -1)
  void bytes(byte[] body);            // sendResponseHeaders(status, body.length)
  void text(String body);             // utf-8; sets Content-Type if unset
  void json(Object body);             // jsonWriter; sets Content-Type if unset
  void problem(ProblemDetail pd);     // application/problem+json

  // streaming terminals
  OutputStream stream();              // chunked; sendResponseHeaders(status, 0)
  OutputStream stream(long length);   // known length
}
```

State machine, enforced via `IllegalStateException`:

- exactly one terminal call per `Request`;
- `header(...)` / `contentType(...)` only before the terminal call;
- streaming terminals return an `OutputStream` the handler is responsible for
  closing (the framework also closes it as a safety net when the exchange ends).

Empty bodies use `responseLength = -1` per the existing project convention
(0 triggers chunked encoding).

### `RequestHandler`

```java
@FunctionalInterface
public interface RequestHandler {
  void handle(Request request) throws IOException;
}
```

`IOException` is kept on the signature for response-writing I/O. Unchecked
exceptions continue to flow into the existing `ExceptionFilter` →
`ExceptionHandler` path unchanged.

### Builder shape

```java
OpenApiServer.builder()
    .spec(spec)
    .bodyReader(String mediaType, BodyReader reader)
    .jsonWriter(Function<Object, byte[]> writer)
    .handlers(Map<String, RequestHandler> handlers)      // type changed (breaking)
    .addHandler(String path, HttpHandler extra)          // unchanged — raw HttpHandler
    .exceptionHandler(...)
    .port(...)
    .shutdownTimeoutSeconds(...)
    .build();
```

`addHandler(path, HttpHandler)` for extras stays raw — extras are arbitrary
side paths (health, metrics) that don't go through OpenAPI dispatch and don't
benefit from `Request`.

### Filter → dispatcher handoff

`RequestPreparationFilter` reads the body, runs validation, and builds the
`Request` object (including the parsed body, path params, operation ID, and a
reference to the `HttpExchange`). It hands the `Request` to `DispatchHandler`
via an internal, package-private `ScopedValue<Request>`.

The user-visible `Request.CONTEXT` `ScopedValue` and the static
`Request.bytes()` / `.parsed()` / `.operationId()` / `.pathParams()` accessors
are removed. The internal `RequestContext` record is removed.

`DispatchHandler` becomes:

```java
final class DispatchHandler implements HttpHandler {
  static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();
  private final Map<String, RequestHandler> handlers;

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler h = handlers.get(request.operationId());
    if (h == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    h.handle(request);
  }
}
```

## Breaking changes

This is a pre-1.0 library; breaking changes are acceptable.

- `JsonMapper` removed; replaced by `BodyReader`. The builder method
  `jsonMapper(JsonMapper)` becomes `bodyReader("application/json", BodyReader)`.
- Builder method `handlers(Map<String, HttpHandler>)` becomes
  `handlers(Map<String, RequestHandler>)`.
- Static accessors `Request.bytes()` / `Request.parsed()` /
  `Request.operationId()` / `Request.pathParams()` / `Request.current()` and
  the `Request.CONTEXT` `ScopedValue` are removed. Handlers read this data
  from the `Request` parameter.
- The example launcher under `src/test/java/.../start/` is updated as part of
  this change.

## Testing

Existing integration tests (`*IT.java`) exercise the full stack and will be
updated to use the new handler signature. Unit tests cover:

- `BodyReader` registration: defaults wired, user overrides win, missing
  `application/json` reader fails the builder.
- `Request` read API: byte/parsed/operationId/pathParams round-trip.
- `Request` response gateway: each terminal produces the right
  `sendResponseHeaders` length and `Content-Type`; double-terminal throws
  `IllegalStateException`; `header(...)` after terminal throws.
- Streaming terminals: `stream()` uses chunked encoding (length 0);
  `stream(length)` uses the supplied length.
- Form-coercion moved out of `FormUrlEncodedParser` — existing form-body
  validation tests must still pass.

## Migration order

The implementation plan will sequence this as:

1. Introduce `BodyReader`; keep `JsonMapper` as a deprecated adapter.
2. Move form-coercion out of `FormUrlEncodedParser` into the validator path.
3. Build the new `Request` class (read API + response gateway) and the internal `ScopedValue<Request>` handoff; keep the old static `Request` accessors alive temporarily.
4. Introduce `RequestHandler`; update the builder; update example launcher and tests.
5. Remove `JsonMapper`, the static `Request` accessors, and the `RequestContext` record.
