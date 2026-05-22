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
- `**` — matches zero or more characters, may cross `/` boundaries.

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

## Error handling

Unchanged. Misses inside the router throw `NotFoundException`, which the
`ExceptionFilter` (still in front of the router) renders as the standard
problem+json 404.

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

## Out of scope

- Exposing matched portions as path parameters.
- Mid-segment wildcards (`prefix-*.json`).
- Per-method extras (extras still accept any method).
- Wildcards in spec paths (use OpenAPI `{name}` templates).
