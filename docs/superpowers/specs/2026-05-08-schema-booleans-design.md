# Schema Booleans — Design

**Date:** 2026-05-08
**Status:** Approved
**Predecessor:** `2026-05-07-openapi-refactor-design.md` (Section 9, Wave 1 #4 partial)

## Goal

Support JSON Schema 2020-12 boolean schemas in OpenAPI 3.1: a bare `true` or `false` where a schema is expected. `true` accepts any value; `false` rejects any value. The remaining items from Wave 1 #4 (`not`, `const`, top-level `enum`) are already implemented; this spec covers only the boolean-schema piece.

## Decisions

1. **Two new schema records.** `AlwaysSchema` and `NeverSchema` join the sealed `Schema` hierarchy. Names mirror JSON Schema's "always-accepting" / "never-accepting" terminology and let the validator switch read like the spec text.
2. **Parser entry signature change.** `SchemaParser.parse` becomes `parse(Object)` instead of `parse(Map<String, Object>)`. Callers (internal recursive calls and external callers in `Spec.java`) drop the `Map` cast. `AdditionalProperties` keeps its existing Boolean handling — it already converts `true`/`false` to `Allowed` / `Forbidden` before reaching `parse`.
3. **Validator behaviour.** `AlwaysSchema` is a no-op pass (including for `null`); `NeverSchema` always fails with keyword `"false"` and message `"schema rejects all values"`.
4. **Out of scope.** Pre-existing array-items empty-map quirk; `$ref` siblings; combinator branches accepting booleans (depends on `feat/combinators` merging — once it does, the parser change here automatically covers `oneOf: [true]` etc.).

## Schema records

```java
public record AlwaysSchema() implements Schema {
  public Set<TypeName> types() { return Set.of(); }
}

public record NeverSchema() implements Schema {
  public Set<TypeName> types() { return Set.of(); }
}
```

`Schema.java`'s `permits` clause grows by two. `types()` returns empty per the convention used by combinator / ref / const / enum records. The top-level `null` short-circuit in `DefaultValidator.validate(...)` checks `schema.types().contains(NULL)`, so `null` falls through to the switch — which is what we want: `AlwaysSchema` accepts `null` via its case body, `NeverSchema` rejects `null` via its case body.

## Parser

`SchemaParser.parse` switches its parameter type from `Map<String, Object>` to `Object`, with a single dispatch added at the top:

```java
public static Schema parse(Object raw) {
  if (raw instanceof Boolean b) {
    return b ? new AlwaysSchema() : new NeverSchema();
  }
  if (raw instanceof Map<?, ?> map) {
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) map;
    return parseMap(typed);
  }
  throw new IllegalArgumentException("schema must be a boolean or an object, was: " + raw);
}
```

`parseMap` is the existing body of the old `parse` method, renamed. Internal recursive calls (`parseObject` for property values, `parseArray` for `items`, `parseList` for combinator branches once `feat/combinators` lands) drop the cast: `parse(value)` instead of `parse((Map<String, Object>) value)`.

External callers in `src/main/java/com/retailsvc/http/spec/Spec.java` (`parseComponentSchemas`, `parseParameter`, `parseRequestBody`, `parseResponses`) similarly drop their `(Map<String, Object>)` casts on the argument passed to `parse`.

`AdditionalProperties` keeps its current implementation — it dispatches on `null` / `Boolean` / `Map` before constructing a `SchemaConstraint`, so no Boolean ever reaches `parse` from that path. Leaving it alone preserves the existing `AdditionalProperties.Allowed` / `Forbidden` records.

## Validator

Two new branches in the `switch` in `DefaultValidator.validate(...)`:

```java
case AlwaysSchema _ -> { /* accepts any value, including null */ }
case NeverSchema _ -> fail(pointer, "false", "schema rejects all values", value);
```

Pointer is the schema's pointer, matching the convention used for combinator failures. Keyword `"false"` describes the source schema literal that produced the failure.

## Tests

- **Parser unit tests** (`SchemaParserTest`):
  - `parse(Boolean.TRUE)` returns `AlwaysSchema`.
  - `parse(Boolean.FALSE)` returns `NeverSchema`.
  - `parse` of a non-Map / non-Boolean input throws `IllegalArgumentException` with the message format documented above.
  - `parse` of an object whose `properties.x: true` and `properties.y: false` produces an `ObjectSchema` whose two property values are `AlwaysSchema` and `NeverSchema` respectively.
- **Validator unit tests** (`DefaultValidatorDispatchTest`):
  - `AlwaysSchema` accepts a string, an integer, an object map, and `null` (single test exercising several values, or four small tests — implementer's choice).
  - `NeverSchema` rejects every value with keyword `"false"` and message containing `"rejects all values"`. Cover at least: a string, an integer, `null`.
- **Integration test:** extend `src/test/resources/openapi.{yaml,json}` (twins kept in sync per the existing memory entry) with one path — say `/gates` — whose request body schema is:
  ```yaml
  type: object
  required: [open]
  properties:
    open: true     # accepted regardless of type
    blocked: false # any presence rejects the body
  ```
  Two new IT tests in `OpenApiServerIT`:
  - Body containing only `open` (any JSON value) → 200.
  - Body containing `blocked` (any value) → 400 with content-type `application/problem+json` and body containing `"false"`.

## Risk and rollback

- **Parser API break.** The `parse(Map)` → `parse(Object)` signature change is binary-incompatible. The library has no published consumers (`0.0.1-local`), so this is acceptable. Internal callers and tests are all updated in the same PR.
- **Empty-map `items` interaction.** `parseArray` continues to short-circuit `items.isEmpty()` to `NullSchema`. With the new parser, `items: true` would correctly produce `AlwaysSchema` since the input is a Boolean, not a Map. The empty-map edge case is unaffected and remains a pre-existing quirk to be cleaned up separately.
- **Rollback.** Two new records, one parser signature change, two validator cases — straightforward to revert per file.

## Sequencing

Single PR, three commits:

1. `feat`: Schema records (`AlwaysSchema`, `NeverSchema`) + parser entry change + parser unit tests.
2. `feat`: Validator branches + validator unit tests.
3. `test`: Integration fixture extension (`/gates`) + end-to-end tests.

Each commit verifiable with `mvn -q verify`.
