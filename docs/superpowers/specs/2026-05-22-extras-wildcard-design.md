# Extras Wildcard Matching — Design

Date: 2026-05-22
Status: Approved

## Motivation

Extras (registered via `OpenApiServer.Builder.extraRoute(path, handler)`) currently
require an exact path. Some real-world routes need pattern matching:

- `/static/*` to serve any single file under `/static/`
- `/schemas/**/openapi.yaml` to expose the spec at any depth
- `/files/**` to serve a tree of static assets

OpenAPI 3.1 has no wildcard syntax (only `{name}` templates), but extras are
explicitly outside the spec, so we are free to define a small wildcard syntax
just for them.

## Syntax

Two wildcards, usable anywhere in a path (not just trailing):

- `*` — matches exactly one path segment, i.e. one or more characters with no `/`.
- `**` — matches any number of characters including `/`. At a trailing
  position (`/files/**`) it may match zero or more characters and so also
  matches the bare prefix path (`/files/`). Between two literal segments
  (`/schemas/**/openapi.yaml`) it must match at least one full intermediate
  segment — the surrounding slashes still need real content between them.

Patterns containing neither `*` nor `**` are treated as exact paths (current
behaviour, no semantic change).

The matched portion is NOT exposed to the handler. The handler receives the
existing `Request` shape; if it needs to inspect the URI it can already do so
via `Request.rawQuery()` and the underlying exchange.

## Architecture

Replace the current per-route `HttpServer.createContext(extraPath, …)` wiring
with a single shared `ExtrasRouter` registered at `/`.

Why one router instead of per-route contexts:

- JDK's `HttpContext` is literal-prefix only — it cannot host a wildcard.
- A unified router avoids JDK context collisions when multiple wildcards share
  a static prefix (e.g. `/static/*` and `/static/legacy/**`).
- It removes the special-case `NotFoundHandler` registered at `/`; the
  ExtrasRouter takes over the "no match → 404" role.

The basePath context (e.g. `/api/v1`) keeps its existing registration. JDK
picks the longest-prefix context, so spec routes still win for basePath URIs.

### ExtrasRouter

New class `com.retailsvc.http.internal.ExtrasRouter` implementing
`HttpHandler`. Holds a list of compiled extras, each a record of
`(originalPath, Pattern compiled, RequestHandler handler)`. On each request:

1. Look up by exact path first (O(1) map of original-path → handler) for the
    no-wildcard case.
2. If no exact hit, iterate the wildcard list in registration order; first
    regex match wins.
3. If nothing matches, render a 404 via the existing exception path.

The existing `ExtraRouteAdapter` is reused per match — it already builds the
`Request`, invokes the handler, and renders the response.

### Pattern compilation

`PathPattern.compile(String raw)` returns a `Pattern` and a flag `hasWildcard`.
Compilation rules:

- Split on `/`. For each segment:
    - Literal segments → `Pattern.quote(segment)`
    - `*` → `[^/]+`
    - `**` → `.*` (across segments)
    - Mixed segments like `prefix-*.json` are NOT supported in this iteration —
      `*` and `**` must be a whole segment. Reject at compile time.
- Rejoin with `/`, anchor with `^` and `$`.

Validation at boot:

- Pattern must start with `/`.
- No empty segments (`//`).
- `**` may not appear adjacent to another `**` (`/foo/**/**/bar`).
- Compilation failures throw `IllegalStateException` from
  `OpenApiServer.Builder.build()`.

### Wiring changes in `OpenApiServer`

- Remove the per-extra `httpServer.createContext(path, …)` loop.
- Build an `ExtrasRouter` from `handlerConfig.extras()`.
- Register it once at `/` with the `ExceptionFilter`. Drop the existing
  `NotFoundHandler` registration — the router returns 404 itself when no
  extra matches.
- The basePath context registration is unchanged.

## Path-traversal protection

Before any matching, the router validates both the raw URI and the decoded
request path. The raw check catches encoded traversal tricks; the decoded
check catches literal traversal segments. Either failure throws
`BadRequestException` and the `ExceptionFilter` renders problem+json 400.

### Raw URI rules (`HttpExchange.getRequestURI().getRawPath()`)

Reject the request if the raw path contains any of the following — these
have no legitimate use in our routes and are common encoding tricks:

- `%2e` or `%2E` (encoded `.`) — also defeats double-encoding (`%252e…`,
  which decodes once to `%2e…` and would otherwise sneak past the
  decoded-segment check).
- `%2f` or `%2F` (encoded `/`).
- `%5c` or `%5C` (encoded backslash).
- `%00` (encoded NUL — truncation attacks).
- A literal backslash `\` (some libraries treat it as a separator).
- Any control char in U+0000–U+001F or U+007F.

### Decoded path rules (`HttpExchange.getRequestURI().getPath()`)

After the JDK's single-pass percent-decoding, split on `/` and reject if:

- Any segment equals `.` or `..`.
- Any segment is empty (`//` anywhere in the path).
- The decoded path contains NUL (U+0000) or any other control char
  (U+0001–U+001F, U+007F).
- Decoding raises `URISyntaxException` or `IllegalArgumentException`
  (malformed / overlong encoding) — caught and rethrown as
  `BadRequestException`.

### Order

1. Apply raw-URI rules to `getRawPath()`.
2. Decode via `getPath()`; if decoding fails, 400.
3. Apply decoded-path rules.
4. Only then run exact-or-wildcard dispatch.

This runs once per request inside `ExtrasRouter`, before any handler is
consulted, so even handlers that choose to read the URI cannot see a
traversal-laden path.

### Handler responsibility

The router stops traversal at the URI layer; it cannot police what a
handler does with the matched-but-not-exposed path. Any future handler
that maps a request portion to a filesystem location MUST also:

- Resolve the target against a fixed base directory.
- Canonicalise via `Path.toRealPath()` and assert
  `resolved.startsWith(baseReal)`.
- Refuse symlinks that escape the base.

This document does not add such a handler, but the rule is recorded here
so it survives the next time someone adds one.

### Out of scope (deliberate)

The same validation does NOT run inside the basePath spec context — spec
paths are matched against an explicit template set, so a `..` segment
simply fails the exact/template match and yields a normal 404. Adding the
400 check there is mentioned for clarity but not implemented.

## Error handling

Unchanged for normal misses: a request that passes validation but matches
no extra throws `NotFoundException`, rendered by the `ExceptionFilter` as
problem+json 404. Traversal violations throw `BadRequestException` → 400
(see above).

## Testing

Tests added under `src/test/java/com/retailsvc/http/`:

- Unit tests for `PathPattern`:
  - exact path → no wildcard
  - `/files/*` matches `/files/a` but not `/files/a/b` and not `/files/`
  - `/files/**` matches `/files`, `/files/a`, `/files/a/b/c`
  - `/schemas/**/openapi.yaml` matches `/schemas/a/openapi.yaml` and
    `/schemas/a/b/openapi.yaml` but not `/schemas/openapi.yamlx`
  - mixed-segment patterns rejected at compile
  - empty segment rejected
  - adjacent `**/**` rejected

- Integration tests (`ExtrasWildcardIT`):
  - `/static/*` serves matching paths, rejects deeper paths
  - `/files/**` serves any depth
  - `/schemas/**/openapi.yaml` serves the spec at various depths
  - exact extras still work (regression for `ExactUrlMatchingIT` scenarios)
  - basePath spec routes still take precedence over extras
  - path-traversal — all return 400:
    - decoded: `/files/../etc/passwd`, `/files/./x`, `/files//x`
    - single-encoded: `/files/%2e%2e/etc/passwd`, `/files/%2E/x`
    - double-encoded: `/files/%252e%252e/etc/passwd`
    - encoded slash: `/files/%2fetc/passwd`
    - backslash: `/files/..\etc\passwd` (literal and `%5c`)
    - NUL truncation: `/files/x%00.txt`
    - control char: `/files/x%0a/y`
    - malformed encoding: `/files/%zz`

## Out of scope

- Exposing matched portions as path parameters.
- Mid-segment wildcards (`prefix-*.json`).
- Per-method extras (extras still accept any method).
- Wildcards in spec paths (use OpenAPI `{name}` templates).
