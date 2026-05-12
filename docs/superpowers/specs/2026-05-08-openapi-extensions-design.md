# OpenAPI extensions (x-* keys)

**Status:** design approved 2026-05-08
**Source inventory:** `docs/superpowers/specs/2026-05-07-openapi-refactor-design.md` §9, new Wave 2 (originally item #29)
**Driving use case:** consumers attach extensions like `x-permissions: ["pro.promotion.create"]` to operations and expect to retrieve them from the typed model in order to drive auth/permission logic in their own filters.

## Goal

Preserve OpenAPI specification extensions (`x-*` keys) on the four most-used carriers and expose them through a typed accessor on the parsed model:

- `Spec`
- `Info`
- `Operation`
- every concrete `Schema` record (16 of them)

Today the parser silently drops `x-*` keys. After this change, consumers retrieve them via, e.g.:

```java
Object perms = operation.extensions().get("x-permissions");
```

## Non-goals

- Adding extensions to `Server`, `Parameter`, `RequestBody`, `MediaType`, `Response`. Pragmatic-tier scope per brainstorming; can be added later if/when consumer pain emerges. Adding record components later at this `0.0.1-local` stage is acceptable per the project's "break freely" policy.
- Exposing the per-request `Operation` to user-supplied `HttpHandler` instances. The handler-access path is acknowledged as useful but deferred to its own spec/PR. The unit + parser tests in this spec prove the data lives on the typed model; demonstrating end-to-end handler retrieval is a follow-up.
- Validating extension values / typing them in any way. Pure passthrough — value type is `Object`, consumer casts as needed.
- Detecting or rejecting unknown non-`x-*` keys. Those remain silently ignored, as today.

## Decisions

- **Per-carrier accessor.** Each affected record gains an `extensions()` component / method returning `Map<String, Object>`. No separate side-channel API on `Spec`.
- **Immutable.** Returned map is `Map.copyOf(...)` of the extracted entries; empty when none.
- **Stable iteration order.** Underlying collection is `LinkedHashMap` before the `Map.copyOf`, so consumers iterating get insertion order from the raw map.
- **`x-*` prefix only.** Strict `startsWith("x-")` filter. No special handling for `x_`, `X-`, etc.
- **Value type is `Object`.** Mirrors how the parser receives values from the consumer-supplied JSON/YAML mapper.

## Record shape changes

- `Spec` — add `Map<String, Object> extensions` as the final record component.
- `Info` — add the same component.
- `Operation` — add the same component.
- `Schema` (sealed interface) — add abstract method `Map<String, Object> extensions();` next to the existing `Set<TypeName> types();`. Every concrete record (`StringSchema`, `NumberSchema`, `IntegerSchema`, `BooleanSchema`, `NullSchema`, `ObjectSchema`, `ArraySchema`, `OneOfSchema`, `AnyOfSchema`, `AllOfSchema`, `NotSchema`, `ConstSchema`, `EnumSchema`, `RefSchema`, `AlwaysSchema`, `NeverSchema`) gains an `extensions` component.

Constructors at every existing call site need a new argument; `Map.of()` is supplied where the parser sees no `x-*` keys.

## Parser changes

A single small helper, package-private to `com.retailsvc.http.spec`:

```java
static Map<String, Object> extractExtensions(Map<String, Object> raw) {
  Map<String, Object> out = new LinkedHashMap<>();
  for (var e : raw.entrySet()) {
    if (e.getKey().startsWith("x-")) {
      out.put(e.getKey(), e.getValue());
    }
  }
  return Map.copyOf(out);
}
```

Call sites:

- `Spec.from(raw)` — pass `extractExtensions(raw)` to the new `Spec` constructor.
- `parseInfo(raw)` — pass `extractExtensions(raw)` to the new `Info` constructor.
- `parseOperation(...)` — pass `extractExtensions(raw)` to the new `Operation` constructor.
- `SchemaParser.parse(rawMap)` — extract once at the top of each `parseXxxSchema` branch and thread into every record constructor.

For schemas where the helper isn't trivially reachable (different package), duplicate the helper as package-private inside `com.retailsvc.http.spec.schema` rather than widening visibility — the implementation is three lines.

## Behavior preserved

- Validation paths are untouched. `x-*` keys are not validated.
- Unknown non-`x-*` keys remain silently ignored, exactly as today.
- The two existing test fixtures (`openapi.json`, `openapi.yaml`) without `x-*` keys must continue to parse identically and produce records whose `extensions()` returns `Map.of()`.

## Tests

Unit tests in `src/test/java/com/retailsvc/http/spec/`:

- `SpecExtensionsTest` — top-level spec with `x-vendor-build: "abc"`; assert `spec.extensions().get("x-vendor-build")` returns `"abc"`. Empty case: spec without any `x-*` returns `Map.of()`.
- `InfoExtensionsTest` — `info` block with `x-contact-team: "platform"`; assert `spec.info().extensions().get(...)`.
- `OperationExtensionsTest` — operation with `x-permissions: ["pro.promotion.create"]`; assert `operation.extensions().get("x-permissions")` equals `List.of("pro.promotion.create")`.

Schema unit tests in `src/test/java/com/retailsvc/http/spec/schema/`:

- `SchemaParserExtensionsTest` — covers `ObjectSchema` and `StringSchema` (representatives of the larger family; all 14 schemas share the same extraction path in `SchemaParser`). Add at least one test for a combinator (`OneOfSchema`) and one for a primitive that takes few other keywords (`BooleanSchema`) to lock in coverage of the "thin" record paths.

Round-trip test:

- Add `x-permissions: ["pro.promotion.create"]` to one operation (e.g., `create-promotion`-style) in `src/test/resources/openapi.json` and mirror in `src/test/resources/openapi.yaml` (project rule: fixtures must mirror).
- New test parses the fixture via the production code path and asserts the value flows through to the typed `Operation`.

No integration test is added in this PR because handler access to `Operation` is out of scope; that's the round-trip test's job.

## Acceptance criteria

- Every affected record (`Spec`, `Info`, `Operation`, all 16 `Schema` permits) exposes `extensions()` returning a non-null immutable `Map<String, Object>`.
- An `x-*` key on the corresponding raw map is present in the returned map; a non-`x-*` key is not.
- A carrier with no `x-*` keys returns `Map.of()` (equal-to-empty, not null).
- Existing unit and IT suites continue to pass — `mvn verify` green.
- Test fixtures `openapi.json` and `openapi.yaml` remain in sync.
- No new runtime dependencies.
