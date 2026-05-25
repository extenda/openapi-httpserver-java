# CORS preflight handler

**Date:** 2026-05-25
**Status:** Design — ready for implementation plan

## Problem

Browsers send a CORS preflight (`OPTIONS` with `Origin` and
`Access-Control-Request-Method` headers) before any non-simple
cross-origin request. The library's OpenAPI router dispatches strictly
by `operationId`, and OpenAPI specs typically do not declare `OPTIONS`
operations — so today, every preflight to a service built on this
library ends in `405 Method Not Allowed` and the cross-origin request
never goes through.

We want a ready-to-use `RequestHandler` factory on `Handlers` that
answers preflights correctly and is wired in via the existing
`extraRoute(...)` mechanism, mirroring how `aliveHandler` and
`healthHandler` are exposed.

## Goals

1. Add two overloads on `Handlers`:
    - `corsPreflightHandler(List<String> allowedOrigins, List<HttpMethod> allowedMethods, List<String> allowedHeaders, boolean allowCredentials, Duration maxAge)`
    - `corsPreflightHandler(Predicate<String> originAllowed, List<HttpMethod> allowedMethods, List<String> allowedHeaders, boolean allowCredentials, Duration maxAge)`
    - The list overload delegates to the predicate overload (`origins::contains`).
2. Validate inputs at construction:
    - Non-null lists/predicate.
    - `allowedMethods` non-empty.
    - `maxAge` (if non-null) non-negative and ≤ `Integer.MAX_VALUE` seconds.
3. On request:
    - Non-OPTIONS → `405` with `Allow: OPTIONS`.
    - OPTIONS with missing `Origin` → `400` (RFC 7807 problem+json via
      existing `BadRequestException` path).
    - OPTIONS with missing `Access-Control-Request-Method` → `400`.
    - Origin not allowed → `403` (no CORS headers in response, so the
      browser blocks).
    - Requested method not in `allowedMethods` → `403`.
    - Requested headers (`Access-Control-Request-Headers`, comma-split,
      case-insensitive) include a header not in `allowedHeaders` → `403`.
    - All checks pass → `204 No Content` with `responseLength = -1` and:
      - `Access-Control-Allow-Origin: <echoed origin>`
      - `Access-Control-Allow-Methods: <comma-joined allowedMethods>`
      - `Access-Control-Allow-Headers: <comma-joined allowedHeaders>`
        (omitted if `allowedHeaders` empty)
      - `Access-Control-Allow-Credentials: true` (only if
        `allowCredentials` true)
      - `Access-Control-Max-Age: <seconds>` (only if `maxAge` non-null)
      - `Vary: Origin` (always)
4. README snippet showing `extraRoute("/api/*", Handlers.corsPreflightHandler(...))`.

## Non-goals

- No `Access-Control-Expose-Headers` support — that header belongs on
  the *actual* response, not the preflight, so it is a `ResponseDecorator`
  concern. Out of scope; revisit as a separate `corsResponseDecorator`
  later if needed.
- No first-class `OpenApiServer.builder().cors(...)` method. The
  primitive is a `RequestHandler` factory; wiring goes through the
  existing `extraRoute` mechanism (wildcard paths already supported).
- No deriving `Allow-Methods` from the OpenAPI spec. Caller supplies
  explicit lists; this keeps the handler self-contained and predictable.
- No interceptor-based auto-application. Caller decides which paths
  receive preflight handling.

## Design

### API

```java
public static RequestHandler corsPreflightHandler(
    List<String> allowedOrigins,
    List<HttpMethod> allowedMethods,
    List<String> allowedHeaders,
    boolean allowCredentials,
    Duration maxAge);

public static RequestHandler corsPreflightHandler(
    Predicate<String> originAllowed,
    List<HttpMethod> allowedMethods,
    List<String> allowedHeaders,
    boolean allowCredentials,
    Duration maxAge);
```

`HttpMethod`, `RequestHandler`, `Response`, and `BadRequestException`
already exist and are reused as-is. No new public types.

### Internals

A single private static helper inside `Handlers.java` does the
validation switch and assembles the response. Request header reads use
the same `req.headers().firstValue(...)` pattern the existing handlers
use. Header-name comparison for `Access-Control-Request-Headers` is
case-insensitive (HTTP semantics).

The `allowedHeaders` list is normalised to lower-case once at
construction time and stored as an unmodifiable `Set<String>` so the
per-request comparison is O(1).

### Wire example

Request:

```
OPTIONS /api/products HTTP/1.1
Origin: https://app.example.com
Access-Control-Request-Method: POST
Access-Control-Request-Headers: content-type, authorization
```

Response (with `allowCredentials=true`, `maxAge=Duration.ofMinutes(10)`):

```
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, PUT, DELETE
Access-Control-Allow-Headers: content-type, authorization
Access-Control-Allow-Credentials: true
Access-Control-Max-Age: 600
Vary: Origin
```

## Testing

`HandlersTest.java` gains the following methods (camelCase, AssertJ +
JUnit static imports, curly braces always, `HttpURLConnection`
constants for status codes):

- `corsPreflightHandlerReturns204WithExpectedHeadersOnValidPreflight`
- `corsPreflightHandlerEchoesOriginAndIncludesVary`
- `corsPreflightHandlerOmitsAllowCredentialsWhenFalse`
- `corsPreflightHandlerOmitsMaxAgeWhenNull`
- `corsPreflightHandlerEmitsMaxAgeInSecondsWhenSet`
- `corsPreflightHandlerOmitsAllowHeadersWhenListEmpty`
- `corsPreflightHandlerRejectsNonOptionsWith405AndAllowOptions`
- `corsPreflightHandlerRejectsMissingOriginWith400`
- `corsPreflightHandlerRejectsMissingRequestMethodWith400`
- `corsPreflightHandlerRejectsDisallowedOriginWith403`
- `corsPreflightHandlerRejectsDisallowedMethodWith403`
- `corsPreflightHandlerRejectsDisallowedHeaderWith403`
- `corsPreflightHandlerMatchesHeadersCaseInsensitively`
- `corsPreflightHandlerListOverloadDelegatesToPredicate`
- Constructor-validation: nulls, empty methods, negative maxAge.

No new integration test — no wiring change to `OpenApiServer`.

## Documentation

Short README section under "Built-in handlers" with one usage snippet:

```java
server = OpenApiServer.builder()
    .extraRoute("/api/*", Handlers.corsPreflightHandler(
        List.of("https://app.example.com"),
        List.of(GET, POST, PUT, DELETE),
        List.of("content-type", "authorization"),
        true,
        Duration.ofMinutes(10)))
    .handlers(operations)
    .build();
```
