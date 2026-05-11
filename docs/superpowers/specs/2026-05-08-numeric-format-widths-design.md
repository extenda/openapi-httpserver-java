# Numeric format width validation (Wave 2 item 8)

**Status:** design approved 2026-05-08
**Source inventory:** `docs/superpowers/specs/2026-05-07-openapi-refactor-design.md` §9, Wave 2 item 8

## Goal

Honor `format` on `IntegerSchema` and `NumberSchema` for the four OpenAPI-defined numeric widths:

- `int32` — value must fit in 32-bit signed (`[Integer.MIN_VALUE, Integer.MAX_VALUE]`).
- `int64` — recognized, always passes (already enforced by the validator's internal `long` coercion).
- `float` — value's magnitude must not exceed `Float.MAX_VALUE` (cast to `float` would otherwise yield ±Infinity). NaN / Infinity inputs also fail.
- `double` — recognized, always passes (already enforced by the validator's internal `double` coercion).

Today `validateStringFormat` exists, but `validateInteger` / `validateNumber` ignore the `format` field entirely.

## Non-goals

- Decimal-precision validation for `float` (option B from brainstorming, rejected). A strict `(float)n != n` check would reject nearly all legitimate non-integer JSON values (`0.1`, `1.1`, …). Industry validators (AJV, jsonschema-validator) check overflow only.
- BigInteger / BigDecimal inputs larger than `long` / `double`. Those already fail upstream with `"type" expected integer/number` and never reach the format check.
- Consumer-defined numeric formats / SPI. Deferred, non-breaking to add later (mirroring the decision made for string formats).
- Toggling assertion vs. annotation behavior — we always assert.
- Changes to `IntegerSchema` / `NumberSchema` record shapes or `Spec` parsing.

## Decisions

- **Overflow only for `float`.** Matches widespread validator behavior.
- **`int64` and `double` are recognized no-ops.** Documents that they're known formats rather than unknown-and-ignored. Same pattern Wave 2 #5 used for `binary` / `password`.
- **Unknown numeric formats remain silently ignored.** Consistent with the string-format contract.

## Per-format strategy

| Format  | Schema       | Predicate                                                              | Failure message              |
|---------|--------------|------------------------------------------------------------------------|------------------------------|
| `int32` | `IntegerSchema` | `n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE`                  | `"value does not fit in int32"` |
| `int64` | `IntegerSchema` | `n -> true`                                                          | `"value does not fit in int64"` (unreachable) |
| `float` | `NumberSchema`  | `!Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= Float.MAX_VALUE` | `"value does not fit in float"` |
| `double`| `NumberSchema`  | `n -> true`                                                          | `"value does not fit in double"` (unreachable) |

## Code organization

Two new dispatch maps inside `DefaultValidator`, mirroring the `FORMAT_CHECKS` pattern used for strings:

```java
private record IntegerFormatCheck(LongPredicate isValid, String message) {}
private record NumberFormatCheck(DoublePredicate isValid, String message) {}

private static final Map<String, IntegerFormatCheck> INTEGER_FORMAT_CHECKS = Map.of(
    "int32", new IntegerFormatCheck(
        n -> n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE,
        "value does not fit in int32"),
    "int64", new IntegerFormatCheck(n -> true, "value does not fit in int64"));

private static final Map<String, NumberFormatCheck> NUMBER_FORMAT_CHECKS = Map.of(
    "float", new NumberFormatCheck(
        n -> !Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= Float.MAX_VALUE,
        "value does not fit in float"),
    "double", new NumberFormatCheck(n -> true, "value does not fit in double"));
```

Two new private methods:

```java
private void validateIntegerFormat(long n, String format, String pointer);
private void validateNumberFormat(double n, String format, String pointer);
```

Each is a single map lookup; missing key → no-op (preserves the "unknown format silently ignored" contract).

Called from the existing `validateInteger` / `validateNumber` at the end, guarded by `s.format() != null`.

Failure renders via the existing `fail(pointer, FORMAT_KEYWORD, message, n)` path — same RFC 7807 400 response shape as string-format failures.

## Tests

Add to `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java` (despite the name, this file already covers integer and number formats):

- `integerFormatInt32` — `Integer.MAX_VALUE` passes; `Integer.MAX_VALUE + 1L` and `Integer.MIN_VALUE - 1L` fail with keyword `format`.
- `integerFormatInt64NoOp` — `Long.MAX_VALUE`, `Long.MIN_VALUE`, and arbitrary mid-range values pass.
- `numberFormatFloat` — `1.5` passes; `1e40` fails with keyword `format`. Negative overflow (`-1e40`) also fails.
- `numberFormatDoubleNoOp` — `Double.MAX_VALUE`, `-Double.MAX_VALUE`, small values pass.
- `integerFormatUnknownIsIgnored` / `numberFormatUnknownIsIgnored` — lock in the silent-ignore contract for unknown formats.

Integration coverage: one IT case in `src/test/java/com/retailsvc/http/OpenApiServerIT.java` exercising `format: int32` via a query parameter, asserting 400 + `application/problem+json` on overflow input and 200 on a valid value. Test fixtures: add the corresponding operation to `src/test/resources/openapi.json` and mirror it in `src/test/resources/openapi.yaml` (project rule).

## Acceptance criteria

- `int32` values outside the 32-bit signed range produce a 400 with `format` in the violation pointer.
- `int64` / `double` formats are recognized but never produce failures from format checks alone (type/range checks elsewhere are unchanged).
- `float` values whose magnitude exceeds `Float.MAX_VALUE`, plus NaN/Infinity inputs, produce a 400.
- Unknown numeric `format` values are still silently ignored.
- String format behavior (Wave 2 item 5) is unchanged byte-for-byte.
- No new runtime dependencies.
- `mvn verify` passes.
