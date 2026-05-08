# OpenAPI 3.1 Combinators — Design

**Date:** 2026-05-08
**Status:** Approved
**Predecessor:** `2026-05-07-openapi-refactor-design.md` (Section 9, Wave 1 #3 and partial #4)

## Goal

Implement runtime validation for the four JSON Schema 2020-12 combinator keywords used in OpenAPI 3.1: `allOf`, `anyOf`, `oneOf`, `not`. The schema records, sealed interface, and parser already produce the corresponding `AllOfSchema` / `AnyOfSchema` / `OneOfSchema` / `NotSchema` records; `DefaultValidator` currently throws `UnsupportedOperationException` for each. Replace those branches with real validation, and update the parser so combinators can co-exist with sibling base assertions (`type`, `properties`, `required`, etc.) per JSON Schema semantics.

## Decisions

1. **Composition with sibling assertions.** When a schema map contains both a combinator keyword and base assertions, the parser emits an implicit `AllOfSchema` whose elements are the parsed base schema and each combinator. This matches JSON Schema 2020-12's "all keywords at one level are conjoined" rule.
2. **Error reporting.** Fail-fast with a single `ValidationError`. For `oneOf` / `anyOf` / `not`, emit a generic message keyed by the combinator name. For `allOf`, propagate the first failing branch's `ValidationError` unchanged.
3. **Evaluation strategy.** Internal evaluation is dictated by semantics — `anyOf` short-circuits on first match, `oneOf` must evaluate every branch to count matches, `allOf` short-circuits on first failure, `not` runs its inner schema once. Branch failures are captured by catching `ValidationException`; this is a deliberate control-flow use of exceptions, not an error-handling pattern.
4. **Out of scope (deferred).** Schema booleans (`true` / `false` as a bare schema), `discriminator`, and multi-error collection. These remain on the gap inventory (Wave 1 #4 partial, Wave 6 #25, #26).

## Validator

Replace the four UOE branches in `DefaultValidator.validate(...)`:

```java
case AllOfSchema(List<Schema> parts) -> {
  for (Schema p : parts) validate(value, p, pointer);
}

case AnyOfSchema(List<Schema> options) -> {
  for (Schema o : options) {
    try { validate(value, o, pointer); return; }
    catch (ValidationException ignored) { /* try next */ }
  }
  fail(pointer, "anyOf", "did not match any anyOf branch", value);
}

case OneOfSchema(List<Schema> options) -> {
  int matched = 0;
  for (Schema o : options) {
    try { validate(value, o, pointer); matched++; }
    catch (ValidationException ignored) { /* count misses */ }
  }
  if (matched != 1) {
    fail(pointer, "oneOf",
        "matched " + matched + " of " + options.size() + " oneOf branches", value);
  }
}

case NotSchema(Schema inner) -> {
  try { validate(value, inner, pointer); }
  catch (ValidationException expected) { return; }
  fail(pointer, "not", "value matched 'not' schema", value);
}
```

The pointer for combinator failures is the schema's pointer (the spot where the combinator is declared) — same convention as other keyword failures. Sub-branch errors do not carry through; the outer message is intentionally generic. Multi-error collection (Wave 6 #25) will revisit this.

## Parser

Currently `SchemaParser.parse(...)` dispatches in priority order — `$ref` → combinator → `const`/`enum` → `type` → permissive object — and emits exactly one record. The change: when a combinator coexists with sibling base assertions, build an `AllOfSchema` whose first element is the parsed base and whose remaining elements are the combinators.

Pseudocode:

```java
if (raw has $ref) return RefSchema(...);

List<Schema> assertions = new ArrayList<>();
Schema base = parseBaseIfPresent(raw);    // existing const/enum/type/object dispatch; null if absent
if (base != null) assertions.add(base);

if (raw has allOf) assertions.addAll(parseAll(raw.allOf));   // flatten one level
if (raw has anyOf) assertions.add(new AnyOfSchema(parseAll(raw.anyOf)));
if (raw has oneOf) assertions.add(new OneOfSchema(parseAll(raw.oneOf)));
if (raw has not)   assertions.add(new NotSchema(parse(raw.not)));

return switch (assertions.size()) {
  case 0 -> permissiveObject;
  case 1 -> assertions.get(0);
  default -> new AllOfSchema(List.copyOf(assertions));
};
```

`allOf` branches flatten directly into the outer assertions list since `AllOf(AllOf(a, b), c)` is semantically equal to `AllOf(a, b, c)`. `anyOf` and `oneOf` are not flattened because their semantics differ from `AllOf`. `parseBaseIfPresent` returns `null` when the schema map has no base-assertion keywords (`type`, `const`, `enum`, or any of the object/array shape keywords) so a vacuous permissive-object isn't injected.

`$ref` continues to be parsed solo. JSON Schema 2020-12 allows siblings to `$ref`, but that interaction is a separate gap; not addressed here.

## Tests

- **Parser, combinator alone:** existing tests for `OneOfSchema` / `AnyOfSchema` / `AllOfSchema` / `NotSchema` round-trip remain green.
- **Parser, combinator + sibling `type`:** new tests asserting the result is `AllOfSchema([base, combinator])`. One per combinator. Covers the primary correctness goal of decision (1).
- **Parser, multiple combinators in one schema:** e.g. `{anyOf: [...], not: ...}` → `AllOfSchema([AnyOfSchema(...), NotSchema(...)])`.
- **Validator, happy path:** one passing test per combinator (`oneOf` exactly one match, `anyOf` first branch matches, `allOf` all branches pass, `not` inner fails so outer passes).
- **Validator, failure path:** `oneOf` zero matches and two-plus matches (separate tests, asserting the count in the message); `anyOf` no match; `allOf` second branch fails (asserts the inner pointer/message propagates); `not` inner passes.
- **Validator, combinator + sibling type:** one polymorphic-body test driven by the parser composition path.
- **Integration:** extend the test fixture (`src/test/resources/openapi.{yaml,json}`) with one operation whose request body uses `oneOf` for a discriminated-style polymorphic shape (no `discriminator` keyword — that's deferred). Add a test handler and assert success on a valid body and 400 on a body that matches zero or two branches. The fixture twins (yaml & json) stay in sync per the existing memory entry.
- **Performance:** k6 smoke run against the example launcher confirms no regression. The combinator dispatch adds a single `case` per request, the catch-blocks only run on combinator paths, and the test schema's existing routes don't use combinators — so the broad k6 numbers should be unchanged.

## Risk and rollback

- **Performance of try/catch in `oneOf`/`anyOf`:** for branches that fail validation, we throw and catch a `ValidationException`. This is fine for typical request volumes but is more allocation-heavy than a boolean predicate. Risk is bounded — combinators are not on the hot path for our existing test fixture. If a future spec uses combinators in tight loops, an internal `boolean tryValidate(...)` overload is an additive optimization.
- **Parser regression:** the dispatch change touches every schema parse. Mitigation: existing parser tests (combinator alone, primitives, refs) keep running and pin behaviour.
- **Rollback:** the change is contained in `SchemaParser` and `DefaultValidator`. Reverting is one revert per file.

## Sequencing

Single PR. Suggested commit shape:

1. Validator: replace UOE branches with real validation; add validator unit tests.
2. Parser: composition path; add parser unit tests for combinator + sibling.
3. Integration: fixture extension + end-to-end test.

Each commit verifiable with `mvn -q verify`.
