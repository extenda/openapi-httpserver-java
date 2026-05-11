# Numeric Format Widths Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Honor `format` on `IntegerSchema` and `NumberSchema` for `int32` (range-check), `int64` (no-op), `float` (overflow-check), `double` (no-op).

**Architecture:** Add two static dispatch maps inside `DefaultValidator` (`INTEGER_FORMAT_CHECKS`, `NUMBER_FORMAT_CHECKS`) mirroring the existing `FORMAT_CHECKS` pattern used for strings. Two new private methods (`validateIntegerFormat`, `validateNumberFormat`) do a single map lookup and call `fail(…)` on a predicate miss. Unknown numeric formats remain silently ignored.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Maven (Surefire / Failsafe).

**Spec:** `docs/superpowers/specs/2026-05-08-numeric-format-widths-design.md`

**Conventions to honor:**
- Google Java Formatter (pre-commit autoruns; never hand-format).
- Always use curly braces — no brace-less one-liners.
- Test method names: camelCase (e.g., `integerFormatInt32`), never `snake_case`.
- `openapi.json` and `openapi.yaml` test fixtures must mirror each other.
- Conventional Commits (commitlint enforces).
- No `Co-Authored-By` trailer.
- LSP diagnostics check after each edit; fix type errors immediately.

---

## File Structure

**Modify:**
- `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` — add records, maps, dispatch methods, and the calls from `validateInteger` / `validateNumber`.
- `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java` — add per-format unit tests + unknown-format-ignored tests.
- `src/test/resources/openapi.json` — add one operation exercising `format: int32`.
- `src/test/resources/openapi.yaml` — mirror the JSON change.
- `src/test/java/com/retailsvc/http/OpenApiServerIT.java` — add an IT case for the new operation.

**No new files.**

---

## Task 1: Add `int32` format (plus dispatch plumbing)

This task introduces the dispatch records, maps, and method calls AND adds the first concrete format. Combined because empty plumbing has no observable behavior to test.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Verify baseline is green**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Write the failing test**

Append to `StringIntegerNumberTest.java` (after the existing integer/number tests):

```java
@Test
void integerFormatInt32() {
  IntegerSchema s =
      new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32");
  assertThatCode(() -> v.validate(Integer.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(Integer.MIN_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate(Integer.MAX_VALUE + 1L, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate(Integer.MIN_VALUE - 1L, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#integerFormatInt32`
Expected: FAIL — `Integer.MAX_VALUE + 1L` is not rejected (format currently ignored on integers).

- [ ] **Step 4: Add records, map, dispatch method, and the call site**

In `DefaultValidator.java`:

Add these imports if not already present:
```java
import java.util.function.DoublePredicate;
import java.util.function.LongPredicate;
```

Add near the existing `FormatCheck` record and `FORMAT_CHECKS` map (top of class):

```java
private record IntegerFormatCheck(LongPredicate isValid, String message) {}

private record NumberFormatCheck(DoublePredicate isValid, String message) {}

private static final Map<String, IntegerFormatCheck> INTEGER_FORMAT_CHECKS =
    Map.of(
        "int32",
        new IntegerFormatCheck(
            n -> n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE,
            "value does not fit in int32"));

private static final Map<String, NumberFormatCheck> NUMBER_FORMAT_CHECKS = Map.of();
```

Add the two dispatch methods next to `validateStringFormat`:

```java
private void validateIntegerFormat(long n, String format, String pointer) {
  IntegerFormatCheck check = INTEGER_FORMAT_CHECKS.get(format);
  if (check == null) {
    return;
  }
  if (!check.isValid().test(n)) {
    fail(pointer, FORMAT_KEYWORD, check.message(), n);
  }
}

private void validateNumberFormat(double n, String format, String pointer) {
  NumberFormatCheck check = NUMBER_FORMAT_CHECKS.get(format);
  if (check == null) {
    return;
  }
  if (!check.isValid().test(n)) {
    fail(pointer, FORMAT_KEYWORD, check.message(), n);
  }
}
```

Wire the call into `validateInteger`. Append at the end of the method, after the `multipleOf` block:

```java
    if (s.format() != null) {
      validateIntegerFormat(n, s.format(), pointer);
    }
```

Wire the call into `validateNumber`. Append at the end of the method, after the `multipleOf` block:

```java
    if (s.format() != null) {
      validateNumberFormat(n, s.format(), pointer);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=StringIntegerNumberTest#integerFormatInt32`
Expected: PASS.

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass (no regressions in existing format/min/max tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate integer format 'int32' for 32-bit overflow"
```

---

## Task 2: Add `int64` no-op format

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Write the test**

Append to `StringIntegerNumberTest.java`:

```java
@Test
void integerFormatInt64AcceptsAnyLong() {
  IntegerSchema s =
      new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int64");
  assertThatCode(() -> v.validate(Long.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(Long.MIN_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(0L, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(123L, s, "/v")).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run test — currently passes (unknown format ignored)**

Run: `mvn test -Dtest=StringIntegerNumberTest#integerFormatInt64AcceptsAnyLong`
Expected: PASS. This is fine — the intent is to lock in no-op semantics so a future change cannot accidentally start asserting.

- [ ] **Step 3: Add explicit `int64` registry entry**

In `DefaultValidator.java`, change the `INTEGER_FORMAT_CHECKS` map to:

```java
private static final Map<String, IntegerFormatCheck> INTEGER_FORMAT_CHECKS =
    Map.of(
        "int32",
        new IntegerFormatCheck(
            n -> n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE,
            "value does not fit in int32"),
        "int64", new IntegerFormatCheck(n -> true, "value does not fit in int64"));
```

(The `int64` message is unreachable but documents the slot, mirroring the `binary`/`password` pattern from Wave 2 #5.)

- [ ] **Step 4: Re-run tests**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Recognize integer format 'int64' as no-op"
```

---

## Task 3: Add `float` format

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Write the failing test**

Append to `StringIntegerNumberTest.java`:

```java
@Test
void numberFormatFloat() {
  NumberSchema s =
      new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, "float");
  assertThatCode(() -> v.validate(1.5, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(-1.5, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate((double) Float.MAX_VALUE, s, "/v"))
      .doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate(1e40, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate(-1e40, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate(Double.NaN, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate(Double.POSITIVE_INFINITY, s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#numberFormatFloat`
Expected: FAIL — overflow inputs are not rejected (NUMBER_FORMAT_CHECKS still empty).

- [ ] **Step 3: Add `float` to NUMBER_FORMAT_CHECKS**

Replace the `NUMBER_FORMAT_CHECKS` definition in `DefaultValidator.java` with:

```java
private static final Map<String, NumberFormatCheck> NUMBER_FORMAT_CHECKS =
    Map.of(
        "float",
        new NumberFormatCheck(
            n -> !Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= Float.MAX_VALUE,
            "value does not fit in float"));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=StringIntegerNumberTest#numberFormatFloat`
Expected: PASS.

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate number format 'float' for 32-bit overflow"
```

---

## Task 4: Add `double` no-op format

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Write the test**

Append to `StringIntegerNumberTest.java`:

```java
@Test
void numberFormatDoubleAcceptsAnyDouble() {
  NumberSchema s =
      new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, "double");
  assertThatCode(() -> v.validate(Double.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(-Double.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(0.0, s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(1.5, s, "/v")).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run test — currently passes (unknown format ignored)**

Run: `mvn test -Dtest=StringIntegerNumberTest#numberFormatDoubleAcceptsAnyDouble`
Expected: PASS. Lock-in test, same rationale as Task 2.

- [ ] **Step 3: Add explicit `double` registry entry**

In `DefaultValidator.java`, change the `NUMBER_FORMAT_CHECKS` map to:

```java
private static final Map<String, NumberFormatCheck> NUMBER_FORMAT_CHECKS =
    Map.of(
        "float",
        new NumberFormatCheck(
            n -> !Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= Float.MAX_VALUE,
            "value does not fit in float"),
        "double", new NumberFormatCheck(n -> true, "value does not fit in double"));
```

- [ ] **Step 4: Re-run tests**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Recognize number format 'double' as no-op"
```

---

## Task 5: Lock in `unknown numeric format ignored` contract

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Add the tests**

Append:

```java
@Test
void integerFormatUnknownIsIgnored() {
  IntegerSchema s =
      new IntegerSchema(
          Set.of(TypeName.INTEGER), null, null, null, null, null, "definitely-not-a-format");
  assertThatCode(() -> v.validate(42L, s, "/v")).doesNotThrowAnyException();
}

@Test
void numberFormatUnknownIsIgnored() {
  NumberSchema s =
      new NumberSchema(
          Set.of(TypeName.NUMBER), null, null, null, null, null, "definitely-not-a-format");
  assertThatCode(() -> v.validate(1.5, s, "/v")).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "test: Lock in 'unknown numeric format ignored' contract"
```

---

## Task 6: Wire `int32` end-to-end through `OpenApiServer` (IT)

**Files:**
- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/resources/openapi.yaml`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java`

- [ ] **Step 1: Add the operation to `openapi.json`**

Add inside the `paths` object alongside existing entries:

```json
"/format/int32": {
  "get": {
    "operationId": "format-int32",
    "parameters": [
      {
        "in": "query",
        "name": "n",
        "required": true,
        "schema": {
          "type": "integer",
          "format": "int32"
        }
      }
    ],
    "responses": {
      "200": {
        "description": "OK"
      }
    }
  }
}
```

- [ ] **Step 2: Mirror the change in `openapi.yaml`**

Add the equivalent block in `src/test/resources/openapi.yaml`. Match the existing indentation/style by comparing against an existing operation (e.g., `format-email` or `query-params`).

- [ ] **Step 3: Add the failing IT case**

In `src/test/java/com/retailsvc/http/OpenApiServerIT.java`, find an existing `@Nested` class such as `FormatEmail` and add a parallel `FormatInt32` class. Mirror its style exactly — `newServer(Map.of("format-int32", exchange -> exchange.sendResponseHeaders(200, -1)))`, `newRequest(...)`, the same try/catch/fail wrapper.

```java
@Nested
class FormatInt32 {

  @Test
  void formatInt32ShouldReturnBadRequestOnOverflow() {
    try (var server =
            newServer(
                Map.of(
                    "format-int32",
                    exchange -> exchange.sendResponseHeaders(200, -1)));
        var client = httpClient()) {
      var response =
          client.send(
              newRequest(server, "/format/int32?n=2147483648", "GET", noBody()),
              BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("content-type").orElseThrow())
          .contains("application/problem+json");
      assertThat(response.body()).contains("\"format\"");
    } catch (Exception e) {
      fail(e);
    }
  }

  @Test
  void formatInt32ShouldReturnOkOnValidValue() {
    try (var server =
            newServer(
                Map.of(
                    "format-int32",
                    exchange -> exchange.sendResponseHeaders(200, -1)));
        var client = httpClient()) {
      var response =
          client.send(
              newRequest(server, "/format/int32?n=42", "GET", noBody()),
              BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    } catch (Exception e) {
      fail(e);
    }
  }
}
```

If the file's helper names differ (`newServer`/`newRequest`/`noBody`/`httpClient`), copy the exact pattern used by the `FormatEmail` nested class — that class was added in the previous wave and is the closest template.

- [ ] **Step 4: Run the IT to verify it passes**

Run: `mvn verify -Dit.test=OpenApiServerIT -DfailIfNoTests=false`
Expected: BUILD SUCCESS, both new IT tests pass.

- [ ] **Step 5: Run the full build**

Run: `mvn verify`
Expected: BUILD SUCCESS, all unit + IT tests pass, JaCoCo report generated.

- [ ] **Step 6: Commit**

```bash
git add src/test/resources/openapi.json src/test/resources/openapi.yaml src/test/java/com/retailsvc/http/OpenApiServerIT.java
git commit -m "test: Verify int32 format validation end-to-end via OpenApiServer"
```

---

## Task 7: Final verification

- [ ] **Step 1: Confirm full build is clean**

Run: `mvn verify`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Sanity-check the registries have the expected keys**

Open `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` and confirm:
- `INTEGER_FORMAT_CHECKS` contains exactly: `int32`, `int64`.
- `NUMBER_FORMAT_CHECKS` contains exactly: `float`, `double`.

- [ ] **Step 3: Push the branch**

Per repo memory: `gh` CLI cannot create PRs in this repo — push the branch and let the user open the PR manually.

```bash
git push -u origin HEAD
```

Notify the user the branch is pushed and ready for them to open the PR.
