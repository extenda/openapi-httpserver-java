# Multiple OpenAPI specs per server — design

Status: proposed
Date: 2026-05-22
Branch: `feat/multiple-specs`

## 1. Motivation

`OpenApiServer` today serves exactly one OpenAPI specification. Two real use cases push against that limit:

- **Versioned APIs side-by-side.** During a v1 → v2 migration window the same service may need to expose `/api/v1/*` and `/api/v2/*` simultaneously, each driven by its own spec file.
- **Distinct APIs in one process.** A public API and an internal admin API (different specs, different base paths) sometimes share a deployable for infrastructure reasons.

Both cases share the same property: each spec has its own base path and is otherwise independent. This document describes the smallest viable change that supports them.

## 2. Goals and non-goals

**Goals**

- Allow callers to register more than one `Spec` on a single `OpenApiServer`.
- Scope `operationId` and security-scheme names *per spec*, so v1 and v2 can both declare `getCustomer` or `bearerAuth` without colliding.
- Keep existing single-spec callers source-compatible.
- Reuse the JDK `HttpServer` longest-prefix-match for routing across base paths.

**Non-goals**

- Merging multiple specs into a single composite document.
- Per-spec interceptors, response decorators, after-response hooks, or exception handlers. These remain global to the server; callers needing per-spec branching can switch on the request path or resolved `operationId` inside the hook.
- Cross-spec `$ref` resolution.
- Hot reload or runtime mutation of bindings.

## 3. Decided constraints

These were settled during brainstorming and frame the design:

1. **Per-spec namespace.** `operationId`s and security-scheme names need only be unique within a single spec.
2. **Backward-compatible builder.** Existing `Builder.spec(Spec)`, `Builder.handler(opId, h)`, and `Builder.securityValidator(name, v)` keep working. They are sugar for one implicit `addSpec()` bundle. Mixing them with explicit `addSpec()` calls in the same builder is rejected at build time.
3. **Duplicate basePath is the only path conflict rejected.** Nested prefixes (`/` plus `/api`) are legal — the JDK `HttpServer` resolves them by longest-prefix match.
4. **Global hooks.** `RequestInterceptor`, `ResponseDecorator`, `AfterResponseHook`, `ExceptionHandler`, and extra `/`-style contexts stay registered once on the `Builder` and apply to every binding.

## 4. Public API

One new builder method, with a convenience overload:

```java
public Builder addSpec(
    Spec spec,
    Map<String, RequestHandler> handlers,
    Map<String, SchemeValidator> securityValidators);

public Builder addSpec(Spec spec, Map<String, RequestHandler> handlers);
```

The two-argument overload is equivalent to passing `Map.of()` for `securityValidators` and is rejected by per-binding validation if the spec actually references any security scheme. `addSpec()` may be called more than once; each call adds one spec binding.

Existing single-spec methods remain on the Builder and behave as before, but at `build()` they are folded into one implicit `addSpec(spec, handlers, validators)` bundle. Mixing the implicit (single-spec) form with explicit `addSpec()` calls fails fast:

```
IllegalStateException: use either spec()/handler()/securityValidator() or addSpec(), not both
```

Global hooks (`requestInterceptor`, `responseDecorator`, `afterResponseHook`, `exceptionHandler`, `extraHandler`) keep their existing Builder methods unchanged.

### Example — multi-spec

```java
OpenApiServer server = OpenApiServer.builder()
    .port(8080)
    .addSpec(v1Spec, Map.of(
        "getCustomer", new V1GetCustomer(),
        "listCustomers", new V1ListCustomers()))
    .addSpec(v2Spec, Map.of(
        "getCustomer", new V2GetCustomer(),
        "listCustomers", new V2ListCustomers(),
        "createCustomer", new V2CreateCustomer()))
    .requestInterceptor(loggingInterceptor)
    .build();
```

### Example — single spec (unchanged)

```java
OpenApiServer server = OpenApiServer.builder()
    .port(8080)
    .spec(spec)
    .handler("getCustomer", new GetCustomer())
    .handler("listCustomers", new ListCustomers())
    .build();
```

## 5. Internal architecture

A new package-private record captures everything needed to serve one spec:

```java
record SpecBinding(
    Spec spec,
    Map<String, RequestHandler> handlers,
    Map<String, SchemeValidator> securityValidators,
    DefaultValidator validator,   // built from spec::resolveSchema
    Router router) {              // built from spec
}
```

`OpenApiServer` stores `List<SpecBinding> bindings` instead of the current single `spec` / `handlers` / `securityValidators` fields.

### Build-time wiring

At `Builder.build()`:

1. Each `addSpec()` call (or the synthesised single bundle from the legacy methods) becomes one `SpecBinding`.
2. **Per-binding validation** — identical to today's checks, scoped to each binding:
    - `validateHandlerWiring(binding.spec(), binding.handlers())` — every `operationId` in the spec has a registered handler, and there are no unknown handler keys.
    - `validateSecurityWiring(binding.spec(), binding.securityValidators())` — every security scheme referenced by the spec has a registered validator.
3. **Cross-binding validation** — reject duplicate `spec.basePath()` across bindings, listing the conflicting spec titles (`spec.info().title()`).
4. At least one binding must be present. Empty server is rejected with the existing "Spec must not be null" semantics, adapted to mention bindings.

### Runtime wiring

On server start, the server creates **one `HttpContext` per binding** at `binding.spec().basePath()`, with that binding's filter chain attached. The catch-all `/` context for 404s is only registered if no binding owns `/`. JDK `HttpServer` routes incoming requests to the longest-prefix-matching context, so each request lands in exactly one binding's chain.

## 6. Filter chain

The three existing filters become per-binding instances. They close over a `SpecBinding` instead of the top-level builder state:

- **`ExceptionFilter`** — logic unchanged. Continues to use the *global* `ExceptionHandler`. One instance per binding (cheap).
- **`RequestPreparationFilter`** — uses `binding.validator()` and `binding.router()` directly. Still stores the resolved `operationId` on the exchange as today.
- **`DispatchHandler`** — looks up the handler in `binding.handlers()`. No global handler map exists.

Global hooks (`RequestInterceptor`, `ResponseDecorator`, `AfterResponseHook`) wrap dispatch the same way they do today; there is still only one instance of each, passed into each binding's filter chain at construction.

## 7. Error handling and edge cases

| Scenario | Outcome |
| --- | --- |
| Two bindings with the same `basePath` | `build()` throws `IllegalStateException` listing the conflicting spec titles. |
| Legacy single-spec methods mixed with `addSpec()` in the same builder | `build()` throws `IllegalStateException` directing the caller to pick one form. |
| No bindings registered | `build()` throws — adapts the existing "Spec must not be null" message. |
| Unknown `operationId` (handler missing or extra) | `build()` throws, naming the spec the mismatch belongs to. |
| Unknown security scheme | `build()` throws, naming the spec the scheme belongs to. |
| Request to a path no binding covers | Existing catch-all `/` 404 context handles it. |
| One binding at `/`, another at `/api/v1` | Legal. `/api/v1/*` routes to the v1 binding; everything else to the `/` binding. |
| Two specs with overlapping path templates inside disjoint base paths | Impossible by construction — JDK `HttpContext` dispatches by base path first, then each binding's `Router` matches templates within its own subtree. |

## 8. Testing

Per project memory, do not add new OpenAPI spec fixture files unless strictly necessary. Reuse `src/test/resources/openapi.json` (and its YAML mirror) by parsing it into a `Map<String,Object>`, deep-cloning, and mutating `servers[0].url` per binding before calling `Spec.from(map)` twice. This naturally gives two bindings with identical `operationId`s, which is exactly the per-spec-namespace property we want to exercise.

**Unit (`OpenApiServerTest`)** — new camelCase methods, static AssertJ imports:

- Two bindings with disjoint base paths both serve traffic correctly.
- Identical `operationId`s across bindings dispatch to their own handler (per-spec namespace).
- Duplicate `basePath` across bindings is rejected at `build()`.
- Mixing legacy single-spec methods with `addSpec()` is rejected at `build()`.
- Per-binding `validateHandlerWiring` and `validateSecurityWiring` fail independently.
- Single-spec callers using legacy methods continue to work unchanged (regression net for the "sugar" claim).

**Integration (`MultiSpecServerIT`)** — boots a real `HttpServer`:

- Two bindings derived from the same fixture, mounted at `/api/v1` and `/api/v2`.
- Each route hits its own handler under live HTTP.
- A request to an uncovered path returns 404 via the catch-all context.

## 9. Migration

No code change is required for existing single-spec callers. The legacy `spec()` / `handler()` / `securityValidator()` builder methods stay on the Builder, with the same semantics — they now compose into one implicit `addSpec()` bundle inside `build()`. Documentation will introduce `addSpec()` as the canonical form for new code while keeping the single-spec example in the README valid.

## 10. Out of scope (future work)

- Per-spec global hooks (interceptor/decorator/exception handler).
- Composite spec endpoint (e.g. serving a merged `/openapi.json` listing all bindings).
- Spec hot reload.
- Cross-spec `$ref` resolution.
