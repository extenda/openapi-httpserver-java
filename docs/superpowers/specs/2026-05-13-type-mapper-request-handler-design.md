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
- Delete `JsonMapper`; the user supplies a `TypeMapper` for `application/json` instead. Default form and text mappers wired automatically. Optional Gson-backed default for `application/json` activated when Gson is on the classpath and no user-supplied JSON mapper is registered.
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
- `application/json` — **no static default**; if the user does not register a mapper, the builder probes the classpath for Gson and falls back to a built-in Gson-backed mapper (see below). If Gson is not on the classpath either, `build()` fails with the same "no JSON mapper registered" error.

Lookup: case-insensitive on the media-type subtype (existing `ContentTypeHeader.mediaType` already lowercases).

### Optional Gson fallback for `application/json`

To shrink setup for callers that already use Gson, the library ships an internal Gson-backed `TypeMapper` and auto-registers it when:

1. The builder reaches `build()` and no `TypeMapper` has been registered for `application/json`; and
2. `com.google.gson.Gson` is resolvable on the classpath.

Implementation:

- Gson is an **optional** Maven dependency (`<optional>true</optional>` / `provided`). The library does not pull Gson into consumer classpaths.
- One internal class — `com.retailsvc.http.internal.gson.GsonJsonMapper` — imports Gson directly. The builder loads it reflectively (`Class.forName(...)`) only after probing for Gson, so consumers without Gson never trigger class-loading of that adapter and never see `NoClassDefFoundError`.
- Jackson is **not** auto-detected. Jackson users register a `TypeMapper` explicitly. Auto-providing a default `ObjectMapper` would pick the wrong configuration for most Jackson users (modules, naming, date formats).

Number handling on read:

- Gson's default `fromJson(json, Object.class)` deserialises every JSON number as `Double`. The library's validator has `IntegerSchema`, format-width checks, and NaN/Infinity rejection that assume integral values arrive as `Long`/`Integer`. To avoid surprises, `GsonJsonMapper` is constructed with a custom `TypeAdapter<Object>` that:
  - reads integral JSON numbers (no fraction, no exponent producing a fraction) into `Long`;
  - reads non-integral or out-of-`Long`-range numbers into `Double`;
  - reads everything else (`String`, `Boolean`, `null`, arrays, objects) the way Gson's default does.
- This is a well-known Gson pattern; ~30 lines, tested in isolation.

JSR-310 handling on write:

- The default `Gson` instance is built with `TypeAdapter`s for `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, `LocalDate`, and `LocalTime`. Each adapter writes `value.toString()` — every JSR-310 type's `toString()` already emits ISO-8601, so adapters are ~5 lines each.
- Without these adapters Gson's default would serialise these types using internal field values, which is never what handlers want.
- Read direction is unaffected: the library parses bodies into raw `Object` (`Map<String,Object>` / `List<?>` / `String` / `Long` / `Double` / `Boolean` / `null`). Gson is never asked to construct an `Instant`, so an ISO-8601 datetime in incoming JSON stays a `String` and is validated against `format: date-time` by `DefaultValidator`. The JSR-310 adapters are therefore effectively write-only in this codebase. That is the intended behaviour.

Write caveat — documented in README:

- `GsonJsonMapper.writeTo(value)` calls `gson.toJson(value)` and returns UTF-8 bytes. With the integer-preserving and JSR-310 adapters above, this handles `Map<String,Object>`, `List<?>`, `String`, `Number`, `Boolean`, `null`, and the listed JSR-310 types correctly. For non-ISO date formats, locale-specific serialization, custom naming strategies, or any custom Java type, register a user-supplied `TypeMapper` for `application/json`. The fallback is intended for the "I'm already using Gson and the defaults are fine" case.

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

- `JsonMapper` removed; replaced by `TypeMapper`. Builder method `jsonMapper(JsonMapper)` becomes `bodyMapper("application/json", TypeMapper)`. No deprecated adapter is kept — the cutover happens in a single PR.
- Builder method `handlers(Map<String, HttpHandler>)` becomes `handlers(Map<String, RequestHandler>)`.
- Static accessors `Request.bytes()` / `Request.parsed()` / `Request.operationId()` / `Request.pathParams()` / `Request.current()` and the `Request.CONTEXT` `ScopedValue` are removed. Handlers read this data from the `Request` parameter.
- The example launcher under `src/test/java/.../start/` is updated as part of this change.

## Testing

Existing integration tests (`*IT.java`) exercise the full stack and will be updated to use the new handler signature. Unit tests cover:

- `TypeMapper` registration: defaults wired, user overrides win, missing `application/json` mapper fails the builder when Gson is not on the classpath.
- Gson fallback: with Gson on the classpath and no user JSON mapper, `build()` succeeds and `application/json` round-trips via `GsonJsonMapper`. Integer-preserving `TypeAdapter` returns `Long` for integral numbers and `Double` for fractional / out-of-range numbers. With Gson absent and no user JSON mapper, `build()` fails with the existing error.
- JSR-310 write adapters: serializing `Map.of("ts", Instant.now())`, an `OffsetDateTime`, a `LocalDate`, etc. emits the ISO-8601 string form. One assertion per type.
- Built-in text mapper: round-trip via `readFrom` and `writeTo`; charset handling.
- Built-in form mapper: `readFrom` parses; `writeTo` throws `UnsupportedOperationException`.
- `Request` read API: byte / parsed / operationId / pathParams round-trip.
- `Request` response gateway: each terminal produces the right `sendResponseHeaders` length and `Content-Type`; double-terminal throws `IllegalStateException`; `header(...)` after terminal throws; `body(unknownMediaType, ...)` throws.
- Streaming terminals: `stream()` uses chunked encoding (length 0); `stream(length)` uses the supplied length.
- Form-coercion moved out of `FormUrlEncodedParser` — existing form-body validation tests must still pass.

## Migration order

The implementation plan will sequence this as:

1. Introduce `TypeMapper`; convert form and text built-ins to implement it; add the internal `GsonJsonMapper` (Gson as optional Maven dependency) and the builder's classpath-probe fallback; delete `JsonMapper`; switch the builder to `bodyMapper(String, TypeMapper)`; rewire `RequestPreparationFilter` to use the registered mappers and drop the hardcoded media-type switch.
2. Move form-coercion out of `FormUrlEncodedParser` into the validator path.
3. Build the new `Request` class (read API + response gateway), the internal `ScopedValue<Request>` handoff, and the `RequestHandler` interface; switch `handlers(...)` to `Map<String, RequestHandler>`; update example launcher and tests; delete the static `Request` accessors, the public `ScopedValue`, and the `RequestContext` record.
