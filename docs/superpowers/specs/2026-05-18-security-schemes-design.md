# Security schemes (OpenAPI 3.1 `securitySchemes` + `security`)

## Context

The OpenAPI 3.1 refactor design (`2026-05-07-openapi-refactor-design.md` §9) parked security as **Wave 7 — last** because it touches every operation and benefits from the rest of the typed model being settled. That model is now in place and the main-code Sonar baseline is clean, so we can tackle security as a contained slice.

This spec turns OpenAPI's security metadata into a first-class part of the library: `securitySchemes` is parsed into a typed model, `security` requirements are resolved per operation, and the library extracts credentials and asks the consumer to validate them. The library renders rejections (401/403) so consumers don't have to repeat the challenge-response boilerplate, but never decides whether a credential is *valid* — that stays with the consumer's callback. An opt-out exists for deployments where an external sidecar (OPA/Envoy in GCP) already enforces auth upstream.

## Decisions (locked during brainstorming)

1. **Scope: parse + extract credential.** Library reads `securitySchemes` and `security`, extracts the credential per scheme, and hands it to a consumer-provided callback. Library does not validate the credential itself.
2. **Scheme types v1: `apiKey` (in `header` / `query` / `cookie`), `http` (`bearer`, `basic`).** `oauth2`, `openIdConnect`, `mutualTLS` are parsed-but-unsupported in v1.
3. **Callback keyed by scheme name** (not by scheme type), matching the OpenAPI model where two `bearer` schemes can mean different things.
4. **Library renders rejections.** 401 + `WWW-Authenticate` for missing/malformed credentials, 403 for callback denial. Body is RFC 7807 `application/problem+json`, consistent with parameter-validation failures.
5. **Callback returns `Optional<Object>` principal.** Empty = deny. Non-empty = allow, with the principal attached to the `Request` for the handler to read.
6. **`useExternalAuthentication()` opt-out.** When set, `SecurityFilter` is a no-op for all operations, validator-registration boot check is skipped, and `Request.principal(...)` returns empty for every scheme. Consumers in sidecar deployments build their own principal from headers the sidecar sets, via their own `RequestInterceptor`.

## High-level architecture

Request flow with security added:

```
HttpServer
  ExceptionFilter
    RequestPreparationFilter   (existing: parses body, validates params)
      SecurityFilter           (NEW)
        DispatchHandler
```

`SecurityFilter` is a new step between request preparation and dispatch. Reasons it lives in the filter chain rather than in `RequestInterceptor`:

- Security is a precondition on whether the request should reach the handler — interceptors can be reordered/disabled, filters are mandatory.
- Consumer `RequestInterceptor`s can still run before the handler (e.g. to bind a `ScopedValue` from the resolved principal), but after security has decided.

When `useExternalAuthentication()` is set, the filter still exists in the chain but short-circuits to `next.proceed()`. Keeping it in the chain (rather than conditionally omitted) keeps the chain shape uniform and makes the opt-out visible in logs/traces.

## Spec model additions

New package: `com.retailsvc.http.spec.security`.

```java
public sealed interface SecurityScheme
    permits ApiKey, HttpBearer, HttpBasic, Unsupported {

  record ApiKey(String name, Location location) implements SecurityScheme {
    public enum Location { HEADER, QUERY, COOKIE }
  }
  record HttpBearer(Optional<String> bearerFormat) implements SecurityScheme {}
  record HttpBasic() implements SecurityScheme {}

  /** oauth2 / openIdConnect / mutualTLS — parsed for completeness, fail at boot if referenced. */
  record Unsupported(String type) implements SecurityScheme {}
}

public record SecurityRequirement(Map<String, List<String>> schemes) {
  // schemes: scheme name → required scopes (scopes ignored in v1 since oauth2/oidc aren't supported)
}
```

Additions to `com.retailsvc.http.spec.Spec`:

```java
record Spec(
    ...,
    Map<String, SecurityScheme> securitySchemes,    // NEW (empty map if absent)
    List<SecurityRequirement> security              // NEW (root-level default, empty if absent)
)
```

Additions to `com.retailsvc.http.spec.Operation`:

```java
record Operation(
    ...,
    Optional<List<SecurityRequirement>> security    // NEW
)
```

Semantics:
- `Operation.security() == Optional.empty()` → inherit `Spec.security()`.
- `Operation.security() == Optional.of(emptyList)` → "no security required" override (per OpenAPI 3.1 §4.8.2).
- `Operation.security() == Optional.of(nonEmptyList)` → override root-level requirements with this list.

`Spec.from(Map)` parses the catalog and the requirement lists. Unknown scheme types map to `Unsupported`. Malformed scheme definitions (missing required fields per the OpenAPI spec) throw `IllegalArgumentException` from `Spec.from(...)` — consistent with current behavior on malformed paths/operations.

## Builder API

```java
public final class OpenApiServer.Builder {
  /** Registers a credential validator for the named security scheme. */
  public Builder securityValidator(String schemeName, SchemeValidator validator);

  /** Opts out of in-process enforcement entirely (e.g. OPA/Envoy sidecar deployment). */
  public Builder useExternalAuthentication();
  ...
}

@FunctionalInterface
public interface SchemeValidator {
  /** @return non-empty principal on allow, empty on deny */
  Optional<Object> validate(Request request, Credential credential);
}

public sealed interface Credential permits ApiKeyCredential, BearerCredential, BasicCredential {
  record ApiKeyCredential(String value) implements Credential {}
  record BearerCredential(String token) implements Credential {}
  record BasicCredential(String username, String password) implements Credential {}
}
```

The sealed `Credential` lets consumers share a single validator across multiple scheme names with a `switch` on the credential type if they want, while keeping per-scheme registration as the default.

## SecurityFilter behavior

For each request the filter:

1. Reads the operationId resolved by `RequestPreparationFilter` and looks up the `Operation`.
2. Computes effective requirements: `op.security().orElse(spec.security())`.
3. If effective requirements is empty → `next.proceed()` (no auth required for this operation).
4. Otherwise, evaluates the OR-of-AND:
    - For each `SecurityRequirement` (OR branch):
        - For each scheme in the AND map, extract the credential.
            - **`ApiKey(name, HEADER)`** → `request.headers().firstValue(name)`.
            - **`ApiKey(name, QUERY)`** → first occurrence of `name` in the query string.
            - **`ApiKey(name, COOKIE)`** → first cookie named `name`.
            - **`HttpBearer`** → `Authorization` header must match `Bearer\s+<token>` (case-insensitive scheme word per RFC 6750).
            - **`HttpBasic`** → `Authorization` header must match `Basic\s+<base64>`; base64 must decode to `user:password`.
        - If any credential in the group is missing → record "missing", skip to next OR branch.
        - If a credential is malformed (e.g. Basic with non-base64) → record "malformed", skip to next OR branch.
        - Otherwise call `SchemeValidator.validate(request, credential)` for each scheme.
        - If every validator returns non-empty → group succeeds. Stash `Map<schemeName, Object>` of principals on the exchange under attribute `security.principals`. `next.proceed()`.
5. If no group succeeds → render rejection (see below).

## Rejection rendering

Pick the strongest signal across all attempted groups:

- If at least one group had a callback that returned `Optional.empty()` (credential present and validator said "no") → **403 Forbidden**.
- Otherwise (all groups had missing or malformed credentials) → **401 Unauthorized**.

Headers:
- 401 emits one `WWW-Authenticate` header per distinct scheme attempted. Examples:
  - `Bearer realm="api"` for `HttpBearer`
  - `Basic realm="api"` for `HttpBasic`
  - For `ApiKey` schemes, RFC 7235 has no registered challenge type — we emit a custom advisory header `WWW-Authenticate: ApiKey location=<header|query|cookie>, name="<name>"` since the alternative is to omit the challenge entirely (also valid per spec). Both behaviors are acceptable; pick the informative one.
- 403 emits no `WWW-Authenticate` (the credential was accepted at the protocol level, just not authorized).

Body is `application/problem+json` matching the existing parameter-validation format:

```json
{ "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "credential missing for scheme 'bearerAuth'" }
```

The `detail` is the most specific reason for the *closest-to-success* attempted group (e.g. "credential missing" vs "validator denied for scheme 'apiKeyAuth'").

## Handler access to principal

```java
public final class Request {
  /** Principals keyed by securityScheme name, set by SecurityFilter on success. Empty when no security ran. */
  public Map<String, Object> principals();

  /** Convenience for the common single-scheme case. */
  public Optional<Object> principal(String schemeName);
}
```

Backed by the exchange attribute `security.principals`. Empty map when `useExternalAuthentication()` is set or when the operation had no security requirements.

## Boot-time validation

When `OpenApiServer.builder(spec).build()` runs:

1. If `useExternalAuthentication()` was called: skip the rest of this section.
2. For every `securityScheme` referenced by *any* operation's effective requirements:
    - It must exist in `spec.securitySchemes` (else `IllegalStateException` — spec is malformed).
    - It must not be `Unsupported` (else `IllegalStateException("scheme '<name>' uses unsupported type '<type>'")`).
    - A validator must be registered for its name (else `IllegalStateException("no SchemeValidator registered for security scheme '<name>'")`).

Fail-fast at boot rather than at request time: prevents silent 401s in production when a validator was forgotten.

## External-auth opt-out

`Builder.useExternalAuthentication()` flips a single boolean. Effects:

- `SecurityFilter` short-circuits to `next.proceed()` for every request.
- Boot-time validator check is skipped.
- `Request.principals()` returns an empty map; `Request.principal(name)` returns `Optional.empty()`.
- `securitySchemes` is still parsed and exposed on `Spec` (introspection unaffected).

Consumers in sidecar deployments derive their own identity from headers the sidecar sets (e.g. `X-Authenticated-User`) via a normal `RequestInterceptor`, which can attach a `ScopedValue` or stash on the exchange as they see fit.

## Testing

The acceptance fixture `src/test/resources/openapi.json` (and its YAML mirror) grows a new `paths` group **under a separate prefix** (`/api/v1/secure/...`) with a representative mix:

- `apiKeyAuth` (header `X-API-Key`)
- `bearerAuth` (HTTP bearer)
- `basicAuth` (HTTP basic)
- One operation with `security: []` to verify the per-operation opt-out
- One operation with a two-scheme AND group to verify the AND semantics

**No root-level `security` is added to `openapi.json`.** A root-level requirement would apply to every existing operation, including the ones the k6 acceptance script hits (`/api/v1/data`, `/api/v1/list/objects`, `/api/v1/params/...`), causing all of them to 401. Root-level inheritance is exercised by a dedicated unit-test fixture under `src/test/resources/security/`, not by the shared `openapi.json` that `ServerLauncher` and k6 boot against.

New unit tests cover:
- `SchemeParserTest` — every scheme type parses; unknown type maps to `Unsupported`; missing required fields throw.
- `RequirementResolutionTest` — op override (present/empty/absent) cases; OR-of-AND evaluation table.
- `CredentialExtractionTest` — happy path and malformed Basic, missing header, multiple-cookie selection, query parameter, mixed-case `Bearer`.
- `SecurityFilterTest` — for each combination: allow path stashes principals, deny path renders 403, missing path renders 401 with the right `WWW-Authenticate` headers, `useExternalAuthentication()` bypasses everything.
- `BootValidationTest` — missing validator throws; unsupported scheme throws when referenced; opt-out suppresses both.

Integration test (`SecurityIT`) runs the real `HttpServer`:
- Authenticated request → 200 with principal-derived response body.
- Missing header → 401 with `WWW-Authenticate`.
- Wrong key → 403.
- Opt-out mode: missing credential still passes through to the handler.

**k6 compatibility.** The acceptance script in `acceptance/k6/script.js` sends no `Authorization` headers and hits only the unsecured `/api/v1/...` operations. As long as we don't add a root-level `security` block to `src/test/resources/openapi.json` and don't attach `security` to those existing operations, k6 stays green. The new `/api/v1/secure/...` operations are exercised by JUnit only — not added to the k6 script. A quick `./acceptance/k6/...` run (or the equivalent `xargs -P 30 curl` smoke) is part of the PR verification checklist.

## Out of scope

- `oauth2` / `openIdConnect` / `mutualTLS` — parsed to `Unsupported`, no extraction logic.
- OAuth2 scope checking — the `scopes` list on `SecurityRequirement` is preserved in the model but ignored by the v1 filter.
- Library-side principal types — `Object` is what the callback returns; we don't ship a `Principal` interface or JWT decoder.
- Configurable "external auth" header bindings — the opt-out is all-or-nothing; consumers map sidecar headers themselves.
- Multi-error reporting — a rejected request stops at the first failed group's worst error, matching the current single-error parameter-validation behavior.
- WWW-Authenticate `realm` configurability — hardcoded to `"api"` in v1.
