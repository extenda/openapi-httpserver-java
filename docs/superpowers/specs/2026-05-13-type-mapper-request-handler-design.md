# TypeMapper and RequestHandler

**Date:** 2026-05-13
**Status:** Approved, ready for plan

## Motivation

The library currently hardcodes body parsing inside `RequestPreparationFilter`: a
`switch` on media type dispatches to `FormUrlEncodedParser`, `TextPlainParser`,
or the user-supplied `JsonMapper`. Adding a new media type (XML, CBOR, etc.)
requires editing the filter. The `JsonMapper` name is also misleading once we
treat it as one mapper among several, and the response side has no symmetric
abstraction at all — handlers have to write bytes manually.

Handlers today receive a raw JDK `HttpExchange` and pull request data via
static accessors on `Request` backed by a `ScopedValue<RequestContext>`. The
ScopedValue exists only because `HttpHandler.handle(HttpExchange)` has nowhere
else to carry prepared data. That side channel is unnecessary if handlers
receive their own per-request object directly.

This change introduces two interfaces — `TypeMapper` for pluggable
read/write per media type, and `RequestHandler` for handlers that receive a
`Request` instead of an `HttpExchange` — and folds response writing into
`Request` as a fluent gateway with one-shot and streaming terminals.

## Scope

In scope:

- `TypeMapper` interface (read + write) and per-media-type registration on the builder.
- Rename `JsonMapper` → user-supplied `TypeMapper` for `application/json`; default form and text mappers wired automatically.
- New `RequestHandler` interface; `handlers(...)` builder method changed to `Map<String, RequestHandler>` (breaking).
- `Request` repurposed from a static-accessor utility into the per-request handle handlers receive. Read API mirrors today's `RequestContext`; adds a response gateway with one-shot and streaming terminals.
- Internal `RequestContext` record and public `Request.CONTEXT` `ScopedValue` removed.

Out of scope:

- **Request streaming.** Handlers buffer the request body and validate it against the spec, as today. Streaming requests will be a follow-up; it needs a separate decision about how operations opt out of body validation.
- Wildcard media-type matching (`text/*`, `*/*`).

## Design

### `TypeMapper`

```java
package com.retailsvc.http;

public interface TypeMapper {
  Object readFrom(byte[] body, String contentTypeHeader);
  byte[] writeTo(Object value);
}
```

`contentTypeHeader` on `readFrom` is the full raw `Content-Type` header — required so form and text mappers can resolve `charset` and other parameters. JSON mappers ignore it.

`TypeMapper` is schema-free. Today `FormUrlEncodedParser.parseAndCoerce` takes the body `Schema` to coerce field values; that coercion moves into the existing validator path that already coerces query/path/header parameters, so the form mapper becomes a plain `byte[]` → `Map<String,Object>` step on the read side.

### Built-in defaults

`TypeMapper` applies uniformly to every media type, including the built-ins. Defaults wired by the builder unless overridden:

- `application/x-www-form-urlencoded` — built-in form mapper. `readFrom` parses to `Map<String,Object>`. `writeTo` throws `UnsupportedOperationException`; form-encoded responses are unusual and we won't speculate on the encoding until someone needs it.
- `text/plain` — built-in text mapper. `readFrom` decodes bytes using the charset declared on `Content-Type` (default UTF-8). `writeTo` returns `String.valueOf(value).getBytes(UTF_8)`.
- `application/json` — **no default**; the user must supply a `TypeMapper`. Mirrors the current contract that `jsonMapper(...)` is required.

Lookup: case-insensitive on the media-type subtype (existing `ContentTypeHeader.mediaType` already lowercases).

### `Request`

`com.retailsvc.http.Request` becomes the per-request handle. Concrete final class (no interface — YAGNI; extract later if testability demands it).

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
  void empty();                          // sendResponseHeaders(status, -1)
  void bytes(byte[] body);               // sendResponseHeaders(status, body.length)
  void text(String body);                // utf-8; sets Content-Type if unset
  void json(Object body);                // shorthand for body("application/json", body)
  void body(String mediaType, Object body);  // looks up the registered TypeMapper
  void problem(ProblemDetail pd);        // application/problem+json

  // streaming terminals
  OutputStream stream();                 // chunked; sendResponseHeaders(status, 0)
  OutputStream stream(long length);      // known length
}
```

`body(mediaType, value)` looks up the `TypeMapper` registered for `mediaType`, calls `writeTo(value)`, sets `Content-Type` if not already set, and writes the bytes with `sendResponseHeaders(status, bytes.length)`. Unknown media type → `IllegalStateException`.

`.json(body)` is exactly `body("application/json", body)`. Kept because JSON is dominant and the call site reads better.

State machine, enforced via `IllegalStateException`:

- exactly one terminal call per `Request`;
- `header(...)` / `contentType(...)` only before the terminal call;
- streaming terminals return an `OutputStream` the handler is responsible for closing (the framework also closes it as a safety net when the exchange ends).

Empty bodies use `responseLength = -1` per the existing project convention (0 triggers chunked encoding).

### `RequestHandler`

```java
@FunctionalInterface
public interface RequestHandler {
  void handle(Request request) throws IOException;
}
```

`IOException` is kept on the signature for response-writing I/O. Unchecked exceptions continue to flow into the existing `ExceptionFilter` → `ExceptionHandler` path unchanged.

### Builder shape

```java
OpenApiServer.builder()
    .spec(spec)
    .bodyMapper("application/json", jsonMapper)        // required
    .bodyMapper("application/xml", xmlMapper)          // optional extra
    .handlers(Map<String, RequestHandler> handlers)    // type changed (breaking)
    .addHandler(String path, HttpHandler extra)        // unchanged — raw HttpHandler
    .exceptionHandler(...)
    .port(...)
    .shutdownTimeoutSeconds(...)
    .build();
```

`addHandler(path, HttpHandler)` for extras stays raw — extras are arbitrary side paths (health, metrics) that don't go through OpenAPI dispatch and don't benefit from `Request`.

The builder fails fast at `build()` time if no `TypeMapper` is registered for `application/json`.

### Filter → dispatcher handoff

`RequestPreparationFilter` reads the body, runs validation, and builds the `Request` object (including the parsed body, path params, operation ID, the resolved set of `TypeMapper`s, and a reference to the `HttpExchange`). It hands the `Request` to `DispatchHandler` via an internal, package-private `ScopedValue<Request>`.

The user-visible `Request.CONTEXT` `ScopedValue` and the static `Request.bytes()` / `.parsed()` / `.operationId()` / `.pathParams()` accessors are removed. The internal `RequestContext` record is removed.

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

- `JsonMapper` removed; replaced by `TypeMapper`. Builder method `jsonMapper(JsonMapper)` becomes `bodyMapper("application/json", TypeMapper)`.
- Builder method `handlers(Map<String, HttpHandler>)` becomes `handlers(Map<String, RequestHandler>)`.
- Static accessors `Request.bytes()` / `Request.parsed()` / `Request.operationId()` / `Request.pathParams()` / `Request.current()` and the `Request.CONTEXT` `ScopedValue` are removed. Handlers read this data from the `Request` parameter.
- The example launcher under `src/test/java/.../start/` is updated as part of this change.

## Testing

Existing integration tests (`*IT.java`) exercise the full stack and will be updated to use the new handler signature. Unit tests cover:

- `TypeMapper` registration: defaults wired, user overrides win, missing `application/json` mapper fails the builder.
- Built-in text mapper: round-trip via `readFrom` and `writeTo`; charset handling.
- Built-in form mapper: `readFrom` parses; `writeTo` throws `UnsupportedOperationException`.
- `Request` read API: byte / parsed / operationId / pathParams round-trip.
- `Request` response gateway: each terminal produces the right `sendResponseHeaders` length and `Content-Type`; double-terminal throws `IllegalStateException`; `header(...)` after terminal throws; `body(unknownMediaType, ...)` throws.
- Streaming terminals: `stream()` uses chunked encoding (length 0); `stream(length)` uses the supplied length.
- Form-coercion moved out of `FormUrlEncodedParser` — existing form-body validation tests must still pass.

## Migration order

The implementation plan will sequence this as:

1. Introduce `TypeMapper`; convert form and text built-ins to implement it; convert `JsonMapper` to a deprecated adapter that wraps a `TypeMapper`.
2. Move form-coercion out of `FormUrlEncodedParser` into the validator path.
3. Build the new `Request` class (read API + response gateway) and the internal `ScopedValue<Request>` handoff; keep the old static `Request` accessors alive temporarily.
4. Introduce `RequestHandler`; update the builder (`bodyMapper(...)`, `handlers(Map<String,RequestHandler>)`); update example launcher and tests.
5. Remove `JsonMapper`, the static `Request` accessors, and the `RequestContext` record.
