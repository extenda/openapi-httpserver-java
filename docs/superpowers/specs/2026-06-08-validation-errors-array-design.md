# Validation `errors[]` Array — Design

**Date:** 2026-06-08
**Status:** Approved
**Predecessor:** `2026-05-08-combinators-design.md` (Decision 2 deferred multi-error collection); `2026-05-07-openapi-refactor-design.md` §9 Wave 6 (orig #25, "multi-error collection")

## Goal

Make `oneOf` / `anyOf` validation failures actionable. Today a failed combinator produces a single, opaque problem document — e.g. `{"detail":"matched 0 of 2 oneOf branches","pointer":"/offers/0","keyword":"oneOf"}` — that hides the real cause. The per-branch `ValidationError`s are computed inside `checkOneOf` / `checkAnyOf` and then discarded.

This design **retains** the per-branch errors and surfaces them by reshaping the `application/problem+json` document to the RFC 9457 validation idiom: `pointer` and `keyword` move out of the top level into per-entry objects inside an `errors[]` extension array. Each branch failure becomes one entry. Pointers adopt the RFC's `#/…` JSON-Pointer-in-fragment form.

This is a **breaking change** to the wire shape (top-level `pointer` / `keyword` are removed), shipped as a feature. The current document is already RFC 9457-compliant; this change keeps it compliant while aligning with the RFC's own multiple-validation-error example.

### Worked example (the motivating case)

`POST /promotions` where `offers[]` is `oneOf: [UnitOffer, TotalOffer]` and the body sends `"minQuantity": "2"` (a string where the schema types it `number`).

Before:
```json
{ "type":"about:blank", "title":"Bad Request", "status":400,
  "detail":"matched 0 of 2 oneOf branches", "pointer":"/offers/0", "keyword":"oneOf" }
```

After: both `UnitOffer` and `TotalOffer` fail fast at the *same* shared leaf (`conditions` precedes `reward` in the body, so neither branch reaches `reward`), producing identical branch errors. After de-duplication (Decision 11) the array collapses to the single actionable cause:
```json
{ "type":"about:blank", "title":"Bad Request", "status":400,
  "detail":"matched 0 of 2 oneOf branches",
  "errors":[
    {"pointer":"#/offers/0/conditions/0/itemSet/minQuantity","keyword":"type","detail":"expected number"}
  ] }
```

When branches instead fail at *different* places, every distinct cause is listed, deepest first (Decision 10). For `oneOf: [Cat, Dog]` given a body that nearly matches `Cat` (failing deep inside a nested `collar`) but is far from `Dog` (a shallow type error):
```json
{ "type":"about:blank", "title":"Bad Request", "status":400,
  "detail":"matched 0 of 2 oneOf branches",
  "errors":[
    {"pointer":"#/pet/collar/size","keyword":"type","detail":"expected integer"},
    {"pointer":"#/pet/bark","keyword":"type","detail":"expected boolean"}
  ] }
```
`errors[0]` is the deeper `Cat` failure (`/pet/collar/size`, depth 3 — the branch the payload most resembles), ahead of the shallow `Dog` failure (`/pet/bark`, depth 2).

## Decisions

1. **`errors[]` everywhere, not just combinators.** Every validation failure renders an `errors[]` array. A non-combinator failure (e.g. a type mismatch or missing required property) yields a single-entry array; a `oneOf` / `anyOf` failure yields one entry per failed branch. This keeps one consistent shape rather than "an array only when a combinator is involved."
2. **Top-level `pointer` / `keyword` removed.** They live only inside `errors[]` entries now. This is the approved breaking change. Top-level retains the RFC core members plus `detail`.
3. **Top-level `detail` = the failing node's own message.** For a combinator that means the summary (`"matched 0 of 2 oneOf branches"`); for a leaf failure it is that leaf's message (`"expected number"`). The summary is honest because `errors[]` now carries the specifics. We do **not** promote a "best branch" message to the top level (rejected as guesswork; the array already exposes every branch).
4. **Pointer form `#/…`.** Entries express the body location as a JSON Pointer in a URI fragment (`"#/offers/0"`, root = `"#"`), matching the RFC 9457 example (`"#/age"`). Today's plain `/…` form is replaced.
5. **`keyword` retained as a per-entry extension.** Useful to clients; permitted by the RFC even though its illustrative example omits it.
6. **`instance` not added.** It is optional under RFC 9457, so its absence is not a compliance gap, and the default `ExceptionHandler` is `handle(Throwable)` with no request context by design (its javadoc directs context-aware mapping to a `RequestInterceptor`). A request-path `instance` would require breaking that public SPI. Deferred; a `urn:uuid` correlation id is a possible future additive option.
7. **One level of flattening.** `errors[]` entries are the immediate failed branches of the failing combinator (or the single leaf for a non-combinator failure). A branch that is itself a nested combinator contributes one entry carrying its own summary (`detail` = `"matched 0 of N …"`, `pointer` = the nested combinator's location); its sub-branches are not recursively expanded in v1. This bounds output size and matches the fail-fast model — each branch's `check()` already collapses to a single `ValidationError`.
8. **`oneOf` "too many matches" yields an empty `errors[]`.** When `matched ≥ 2`, the problem is ambiguity, not a bad field; there are no failed-branch errors worth listing. The array is empty and therefore omitted, leaving just the summary `detail`. Same for any failure whose node has no sub-errors.
9. **No happy-path cost.** `anyOf` still short-circuits on the first matching branch (no list built). `oneOf` already must evaluate every branch to count matches; the branch-error list is built only on the failure path (`matched != 1`). `ValidationException.CONSTRUCTIONS` stays at zero for valid combinator bodies.
10. **`errors[]` ordered "most likely first."** Entries are sorted by descending failure depth — the number of path segments in the entry's pointer — so the branch that validated the most structure before failing (most likely the branch the client intended) comes first. Ties keep schema order (stable sort). This is a best-effort heuristic, not a guarantee; it is cheap, deterministic, and degrades gracefully (a "wrong" guess still lists a real branch error). Sorting is a presentation concern applied when building entries (see below); the validator keeps `branches` in natural schema order. A single-entry (leaf) or empty (too-many-matches) array is unaffected.
11. **Identical entries are de-duplicated.** When branches share structure they often fail at the exact same leaf (same `pointer` + `keyword` + `detail`) — the motivating promotion payload does. Such exact duplicates collapse to a single entry, keeping the first occurrence (so order from Decision 10 is preserved). Only fully-identical entries collapse; two failures at the same pointer for different reasons both remain. This removes pure noise without losing information.

## Data model

`ValidationError` gains an ordered list of sub-errors (the failed branches). Existing call sites are preserved with a convenience constructor that defaults to no branches.

```java
public record ValidationError(
    String pointer, String keyword, String message, Object rejectedValue,
    List<ValidationError> branches) {

  public ValidationError(String pointer, String keyword, String message, Object rejectedValue) {
    this(pointer, keyword, message, rejectedValue, List.of());
  }

  public ValidationError {
    branches = List.copyOf(branches);
  }
}
```

- **Leaf error:** `branches` empty. Renders as a single `errors[]` entry built from its own `pointer` / `keyword` / `message`.
- **Combinator error:** `branches` holds each failed branch's `ValidationError`. Renders as one `errors[]` entry per branch.

`ValidationException` is unchanged (its message string still derives from the top-level `pointer` / `keyword` / `message`).

## Validator

`checkOneOf` / `checkAnyOf` in `DefaultValidator` stop discarding branch results.

```java
private Optional<ValidationError> checkAnyOf(Object value, List<Schema> options, String pointer) {
  List<ValidationError> failures = new ArrayList<>();
  for (Schema o : options) {
    Optional<ValidationError> r = check(value, o, pointer);
    if (r.isEmpty()) {
      return OK;                     // short-circuit; no list retained on success
    }
    failures.add(r.get());
  }
  return Optional.of(new ValidationError(
      pointer, "anyOf", "did not match any anyOf branch", value, failures));
}

private Optional<ValidationError> checkOneOf(Object value, List<Schema> options, String pointer) {
  int matched = 0;
  List<ValidationError> failures = new ArrayList<>();
  for (Schema o : options) {
    Optional<ValidationError> r = check(value, o, pointer);
    if (r.isEmpty()) {
      matched++;
    } else {
      failures.add(r.get());
    }
  }
  if (matched == 1) {
    return OK;
  }
  return Optional.of(new ValidationError(
      pointer, "oneOf",
      "matched " + matched + " of " + options.size() + " oneOf branches",
      value,
      matched == 0 ? failures : List.of()));  // ≥2 matches → ambiguity, no field-level errors
}
```

`allOf` / `not` / object / array paths are unchanged — they already propagate the real first-failure `ValidationError`, which now simply renders as a single-entry `errors[]`.

## Problem detail and renderer

`ProblemDetail` drops top-level `pointer` / `keyword` and gains an `errors` list. A nested record models each entry.

```java
public record ProblemDetail(
    String type, String title, int status, String detail, List<Entry> errors) {

  public record Entry(String pointer, String keyword, String detail) {}

  public static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(DEFAULT_TYPE, "Bad Request", 400, e.message(), entriesOf(e));
  }

  public static ProblemDetail forBadRequest(BadRequestException e) {
    List<Entry> errors = e.pointer()
        .map(p -> List.of(new Entry(fragment(p), e.keyword().orElse(null), e.getMessage())))
        .orElseGet(List::of);
    return new ProblemDetail(DEFAULT_TYPE, titleFor(e.status()), e.status(), e.getMessage(), errors);
  }
}
```

- `entriesOf(e)` = one `Entry` per branch when `e.branches()` is non-empty, else a single `Entry` from `e` itself. `fragment(p)` = `"#" + p` (root `""` → `"#"`).
- When there is more than one entry, sort them by descending pointer depth (segment count, i.e. number of `/` in the pre-fragment pointer), stable on ties (Decision 10), then drop exact `(pointer, keyword, detail)` duplicates keeping the first (Decision 11), so the deepest — most-likely-intended — branch is `errors[0]`.
- A `BadRequestException` with no `pointer` produces an empty `errors` list.

`ProblemDetailRenderer` replaces the two top-level `pointer` / `keyword` appends with an `errors` array writer:

- Emit `"errors":[ … ]` only when the list is non-empty (consistent with the existing null-omission rule).
- Each entry is `{"pointer":"#/…","keyword":"…","detail":"…"}`; omit `keyword` within an entry when null (the `BadRequestException`-without-keyword case). `pointer` and `detail` are always present in an emitted entry.
- Reuse `JsonStrings.appendQuoted` for escaping. No JSON library is introduced; GraalVM-native friendliness is preserved.

`Handlers.defaultExceptionHandler` is unchanged in structure — it still calls `ProblemDetail.forValidation` / `forBadRequest` and renders via `ProblemDetailRenderer`.

## Tests

- **`ValidationError`:** convenience constructor defaults `branches` to empty; canonical constructor copies the list (defensive).
- **`DefaultValidator` (unit):**
  - `oneOf` zero matches: top-level message keeps `"matched 0 of N"`; `branches` contains one error per branch with their real pointers/keywords/messages.
  - `oneOf` two matches: `branches` is empty (Decision 8).
  - `anyOf` no match: `branches` contains every branch's error.
  - Happy paths (`oneOf` exactly one, `anyOf` first match): return `OK`, `branches` never built, `ValidationException.CONSTRUCTIONS` unchanged — extend `ValidatorNoThrowOnHappyPathTest`.
  - Nested combinator branch surfaces as a single summary entry (Decision 7).
- **`ProblemDetail` / `ProblemDetailRenderer`:** rewrite `ProblemDetailRendererTest` for the new shape — single-entry `errors[]` for a leaf, multi-entry for a combinator, `#/…` pointer form, `keyword` omitted when absent, `errors` omitted when empty. Round-trip the JSON through the test `JsonMapper`.
- **Ordering (Decision 10):** a combinator failure whose branches fail at different depths puts the deepest entry at `errors[0]`; a test asserts the order, including a stable-on-ties case (two branches failing at equal depth keep schema order).
- **De-duplication (Decision 11):** two branches failing at the identical leaf (the promotion-style shared-structure case) collapse to one entry; two failures at the same pointer with different `keyword`/`detail` both remain.
- **`HandlersDefaultExceptionTest`:** update the `containsEntry("pointer", "/email")` / `containsEntry("keyword", …)` assertions to read from `errors[0]` and expect the `#/email` fragment form.
- **`OpenApiServerIT`:** the existing `contains("pointer")` / `contains("keyword")` substring checks still hold (both keys now live inside entries); add one assertion that a `oneOf` body failure returns multiple `errors[]` entries.
- **Integration (the worked example):** extend the test fixture (`src/test/resources/openapi.{yaml,json}`, kept in sync) with a `oneOf` body whose branches fail at different depths, and assert the response lists the deep leaf error. Prefer mutating the existing fixture per the minimize-fixtures convention rather than adding a new file.

## Risk and rollback

- **Breaking wire change.** Any consumer reading top-level `pointer` / `keyword` breaks. Accepted and released as a feature; called out in the changelog/README. The `#/…` pointer form is an additional value change for clients that parsed the old `/…` form.
- **Output size for wide unions.** A `oneOf` with many branches emits one entry per failed branch. Bounded by branch count and one-level flattening (Decision 7); acceptable. If a pathological spec makes this large, a future cap is additive.
- **Scope boundary.** This is *not* full multi-error collection (orig #25): non-combinator failures remain fail-fast (single entry). Whole-request error gathering stays deferred; the `errors[]` shape introduced here is forward-compatible with it.
- **Rollback.** Contained to `ValidationError`, `DefaultValidator` (two methods), `ProblemDetail`, `ProblemDetailRenderer`, and the affected tests. Revert per file.

## Sequencing

Single PR. Suggested commit shape, each verifiable with `mvn -q verify`:

1. `ValidationError` branches field + `DefaultValidator` `checkOneOf` / `checkAnyOf` retaining branch errors; validator unit tests.
2. `ProblemDetail` reshape + `ProblemDetailRenderer` `errors[]` writer; renderer/handler unit tests.
3. Integration fixture + end-to-end test; README/changelog note on the breaking shape.
