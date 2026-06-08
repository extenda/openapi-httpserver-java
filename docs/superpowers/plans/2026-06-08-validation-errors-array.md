# Validation `errors[]` Array Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface `oneOf`/`anyOf` validation failures by reshaping the `application/problem+json` body to the RFC 9457 idiom — top-level `pointer`/`keyword` move into a per-branch `errors[]` array (deepest-first, de-duplicated).

**Architecture:** `ValidationError` gains a `branches` list; `DefaultValidator.checkOneOf`/`checkAnyOf` populate it on failure instead of discarding branch results. `ProblemDetail` drops top-level `pointer`/`keyword` and carries `List<Entry> errors`, built by an `entriesOf` helper that fragments pointers (`#/…`), sorts by descending depth, and de-dups exact entries. `ProblemDetailRenderer` emits the array with the existing hand-rolled JSON writer (no JSON library).

**Tech Stack:** Java 25, JUnit 5, AssertJ, Maven (Surefire `mvn test`, Failsafe `mvn verify`). Google Java Format enforced by pre-commit. Reference spec: `docs/superpowers/specs/2026-06-08-validation-errors-array-design.md`.

**Conventions (from repo memory):** always use curly braces; camelCase test method names; static imports for AssertJ/JUnit; no inline fully-qualified type names (add imports); LSP/SonarLint are blind to this worktree — rely on `mvn`. Commit subjects are Conventional Commits, capitalised after the colon, **no** `Co-Authored-By` trailer.

---

### Task 1: Add `branches` to `ValidationError`

Adds the sub-error list. Behaviour-neutral and compile-safe: a four-arg convenience constructor keeps every existing `new ValidationError(p, k, m, rv)` call site (the `err(...)` helpers and tests) working.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/ValidationError.java`
- Test: `src/test/java/com/retailsvc/http/validate/ValidationErrorTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/retailsvc/http/validate/ValidationErrorTest.java`:

```java
package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationErrorTest {

  @Test
  void convenienceConstructorDefaultsToNoBranches() {
    var e = new ValidationError("/x", "type", "expected string", null);
    assertThat(e.branches()).isEmpty();
  }

  @Test
  void canonicalConstructorCopiesBranchesDefensively() {
    var branch = new ValidationError("/x/y", "type", "expected number", "s");
    var mutable = new ArrayList<ValidationError>(List.of(branch));

    var e = new ValidationError("/x", "oneOf", "matched 0 of 1 oneOf branches", "s", mutable);
    mutable.clear();

    assertThat(e.branches()).containsExactly(branch);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ValidationErrorTest`
Expected: COMPILE FAILURE — the five-arg constructor and `branches()` accessor do not exist yet.

- [ ] **Step 3: Write minimal implementation**

Replace the entire body of `src/main/java/com/retailsvc/http/validate/ValidationError.java` with:

```java
package com.retailsvc.http.validate;

import java.util.List;

public record ValidationError(
    String pointer, String keyword, String message, Object rejectedValue,
    List<ValidationError> branches) {

  public ValidationError {
    branches = List.copyOf(branches);
  }

  /** Leaf error with no branch sub-errors. */
  public ValidationError(String pointer, String keyword, String message, Object rejectedValue) {
    this(pointer, keyword, message, rejectedValue, List.of());
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ValidationErrorTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/ValidationError.java \
        src/test/java/com/retailsvc/http/validate/ValidationErrorTest.java
git commit -m "feat: Add branch errors to ValidationError"
```

---

### Task 2: Retain branch errors in `checkOneOf` / `checkAnyOf`

The validator stops discarding per-branch results. On the failure path it builds the combinator `ValidationError` with `branches` populated (schema order — sorting/de-dup are a presentation concern done later in `ProblemDetail`). The top-level message is unchanged, so the existing `DefaultValidatorDispatchTest` count assertions stay green, and the happy path still allocates no list.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java:529-553` (`checkAnyOf`, `checkOneOf`)
- Test: `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java` (add tests)

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java`, add these three methods inside the class (next to `oneOfFailsWhenZeroBranchesMatch`). They rely on the existing `v` validator and `stringSchema(min, max)` helper already in that file:

```java
  @Test
  void oneOfZeroMatchesCapturesEachBranchError() {
    // "hello" (len 5): branch[0] minLength 100 fails, branch[1] maxLength 2 fails.
    var schema = new OneOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)), Map.of());
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            t -> {
              var err = ((ValidationException) t).error();
              assertThat(err.branches())
                  .extracting(ValidationError::keyword)
                  .containsExactly("minLength", "maxLength");
            });
  }

  @Test
  void oneOfTwoMatchesHasNoBranchErrors() {
    // both branches accept "hello" — ambiguity, not a field error.
    var schema = new OneOfSchema(List.of(stringSchema(null, 10), stringSchema(1, null)), Map.of());
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(t -> assertThat(((ValidationException) t).error().branches()).isEmpty());
  }

  @Test
  void anyOfNoMatchCapturesEachBranchError() {
    var schema = new AnyOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)), Map.of());
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            t -> {
              var err = ((ValidationException) t).error();
              assertThat(err.branches())
                  .extracting(ValidationError::keyword)
                  .containsExactly("minLength", "maxLength");
            });
  }
```

If `ValidationError` is not already imported in this test file, add `import com.retailsvc.http.validate.ValidationError;` — it is in the same package `com.retailsvc.http.validate`, so no import is needed; reference `ValidationError::keyword` directly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest`
Expected: FAIL — `branches()` is empty (the current `checkOneOf`/`checkAnyOf` discard branch errors), so `containsExactly("minLength","maxLength")` fails.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`, replace the two methods `checkAnyOf` and `checkOneOf` (currently lines 529-553) with:

```java
  private Optional<ValidationError> checkAnyOf(Object value, List<Schema> options, String pointer) {
    List<ValidationError> failures = new ArrayList<>();
    for (Schema o : options) {
      Optional<ValidationError> result = check(value, o, pointer);
      if (result.isEmpty()) {
        return OK;
      }
      failures.add(result.get());
    }
    return Optional.of(
        new ValidationError(pointer, "anyOf", "did not match any anyOf branch", value, failures));
  }

  private Optional<ValidationError> checkOneOf(Object value, List<Schema> options, String pointer) {
    int matched = 0;
    List<ValidationError> failures = new ArrayList<>();
    for (Schema o : options) {
      Optional<ValidationError> result = check(value, o, pointer);
      if (result.isEmpty()) {
        matched++;
      } else {
        failures.add(result.get());
      }
    }
    if (matched == 1) {
      return OK;
    }
    return Optional.of(
        new ValidationError(
            pointer,
            "oneOf",
            "matched " + matched + " of " + options.size() + " oneOf branches",
            value,
            matched == 0 ? failures : List.of()));
  }
```

`java.util.ArrayList` and `java.util.List` are already imported in this file. No new imports needed.

- [ ] **Step 4: Run the full validator + happy-path suites**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest,ValidatorNoThrowOnHappyPathTest`
Expected: PASS. The pre-existing `oneOfFailsWhenZeroBranchesMatch` / `oneOfFailsWhenTwoBranchesMatch` / `oneOfWithEmptyOptionsAlwaysFails` count assertions still hold (message unchanged); `failingOneOfConstructsExactlyOneValidationException` still passes (branch `ValidationError`s do not increment `ValidationException.CONSTRUCTIONS`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java
git commit -m "feat: Retain oneOf/anyOf branch errors in validator"
```

---

### Task 3: Reshape `ProblemDetail` + renderer to `errors[]` (BREAKING)

This is the breaking wire change. `ProblemDetail`'s constructor signature changes, which forces `ProblemDetailRenderer`, `ProblemDetailRendererTest`, and the assertions in `HandlersDefaultExceptionTest` to change in the **same commit** so the build stays green. (`OpenApiServerIT`'s `contains("pointer")`/`contains("keyword")` substring checks still pass — those keys now live inside entries — so it is not touched here.)

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/ProblemDetail.java`
- Modify: `src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java`
- Test: `src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java` (rewrite)
- Test: `src/test/java/com/retailsvc/http/internal/ProblemDetailTest.java` (create — covers `entriesOf` ordering/de-dup via the public factories)
- Test: `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java` (update two assertions)

- [ ] **Step 1: Write the new renderer test (rewrite the file)**

Replace the entire contents of `src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java` with:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.internal.ProblemDetail.Entry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailRendererTest {

  @Test
  void rendersSingleEntryErrorsArray() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank", "Bad Request", 400, "expected string",
            List.of(new Entry("#/x", "type", "expected string")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"expected string\",\"errors\":[{\"pointer\":\"#/x\","
                + "\"keyword\":\"type\",\"detail\":\"expected string\"}]}");
  }

  @Test
  void rendersMultipleErrorEntries() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank", "Bad Request", 400, "matched 0 of 2 oneOf branches",
            List.of(
                new Entry("#/collar/size", "type", "expected integer"),
                new Entry("#/bark", "type", "expected boolean")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"matched 0 of 2 oneOf branches\",\"errors\":["
                + "{\"pointer\":\"#/collar/size\",\"keyword\":\"type\",\"detail\":\"expected integer\"},"
                + "{\"pointer\":\"#/bark\",\"keyword\":\"type\",\"detail\":\"expected boolean\"}]}");
  }

  @Test
  void omitsKeywordWithinEntryWhenNull() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank", "Unprocessable Content", 422, "email taken",
            List.of(new Entry("#/email", null, "email taken")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Unprocessable Content\",\"status\":422,"
                + "\"detail\":\"email taken\",\"errors\":[{\"pointer\":\"#/email\","
                + "\"detail\":\"email taken\"}]}");
  }

  @Test
  void omitsEmptyErrorsArray() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Unauthorized", 401, "missing token", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"missing token\"}");
  }

  @Test
  void omitsNullDetailAndEmptyErrors() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Not Found", 404, null, List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
  }

  @Test
  void escapesQuoteAndBackslashInDetail() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "a\"b\\c", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"a\\\"b\\\\c\"}");
  }

  @Test
  void escapesNamedControlCharsInDetail() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "\b\f\n\r\t", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"\\b\\f\\n\\r\\t\"}");
  }

  @Test
  void passesThroughNonAsciiCharactersVerbatim() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "café-é", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad"
                + " Request\",\"status\":400,\"detail\":\"café-é\"}");
  }

  private static String asString(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -q test -Dtest=ProblemDetailRendererTest`
Expected: COMPILE FAILURE — `ProblemDetail` has no five-arg `(…, List)` constructor and no nested `Entry` type yet.

- [ ] **Step 3: Reshape `ProblemDetail`**

Replace the entire contents of `src/main/java/com/retailsvc/http/internal/ProblemDetail.java` with:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.validate.ValidationError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Carrier for an RFC 9457 problem+json document. Serialized by {@link ProblemDetailRenderer}; the
 * wire shape is the RFC core members (type, title, status, detail) plus an {@code errors} extension
 * array. Each {@link Entry} locates one validation failure with a JSON-Pointer-in-fragment {@code
 * pointer} and the failed {@code keyword}. {@code type} is always {@code about:blank}, so {@code
 * title} is advisory per the RFC.
 */
public record ProblemDetail(
    String type, String title, int status, String detail, List<Entry> errors) {

  /** One validation failure: its body location, the failed keyword, and a human-readable detail. */
  public record Entry(String pointer, String keyword, String detail) {}

  private static final String DEFAULT_TYPE = "about:blank";

  public static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(DEFAULT_TYPE, "Bad Request", 400, e.message(), entriesOf(e));
  }

  public static ProblemDetail forBadRequest(BadRequestException e) {
    List<Entry> errors =
        e.pointer()
            .map(p -> List.of(new Entry(fragment(p), e.keyword().orElse(null), e.getMessage())))
            .orElseGet(List::of);
    return new ProblemDetail(DEFAULT_TYPE, titleFor(e.status()), e.status(), e.getMessage(), errors);
  }

  /**
   * Flattens a validation error into ordered {@code errors} entries: the failed branches of a
   * combinator (one each), or the single leaf otherwise. Multi-entry results are sorted deepest
   * pointer first (most-likely-intended branch) and de-duplicated on exact equality.
   */
  private static List<Entry> entriesOf(ValidationError e) {
    List<ValidationError> sources = e.branches().isEmpty() ? List.of(e) : e.branches();
    List<Entry> entries = new ArrayList<>(sources.size());
    for (ValidationError s : sources) {
      entries.add(new Entry(fragment(s.pointer()), s.keyword(), s.message()));
    }
    if (entries.size() <= 1) {
      return entries;
    }
    entries.sort(Comparator.comparingInt((Entry en) -> depth(en.pointer())).reversed());
    return new ArrayList<>(new LinkedHashSet<>(entries));
  }

  private static String fragment(String pointer) {
    return "#" + pointer;
  }

  private static int depth(String pointer) {
    int n = 0;
    for (int i = 0; i < pointer.length(); i++) {
      if (pointer.charAt(i) == '/') {
        n++;
      }
    }
    return n;
  }

  private static final Map<Integer, String> TITLES =
      Map.of(
          400, "Bad Request",
          401, "Unauthorized",
          403, "Forbidden",
          404, "Not Found",
          405, "Method Not Allowed",
          409, "Conflict",
          410, "Gone",
          412, "Precondition Failed",
          415, "Unsupported Media Type",
          422, "Unprocessable Content");

  private static String titleFor(int status) {
    return TITLES.getOrDefault(status, "Bad Request");
  }
}
```

- [ ] **Step 4: Rewrite the renderer's `errors[]` writer**

Replace the entire contents of `src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java` with:

```java
package com.retailsvc.http.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Built-in JSON writer for the {@code application/problem+json} (RFC 9457) wire shape. Keeps the
 * exception and security paths free of any {@code TypeMapper}, so the library can emit problem
 * responses without a JSON library on the classpath (and without record-accessor reflection that
 * GraalVM Native Image would otherwise need configured).
 *
 * <p>Null-valued fields and an empty {@code errors} array are omitted.
 */
public final class ProblemDetailRenderer {

  /** Initial capacity sized for a typical problem-detail document. */
  private static final int INITIAL_CAPACITY = 128;

  private ProblemDetailRenderer() {}

  public static byte[] renderJson(ProblemDetail pd) {
    StringBuilder out = new StringBuilder(INITIAL_CAPACITY);
    out.append('{');
    boolean first = true;
    first = appendString(out, first, "type", pd.type());
    first = appendString(out, first, "title", pd.title());
    first = appendInt(out, first, "status", pd.status());
    first = appendString(out, first, "detail", pd.detail());
    appendErrors(out, first, pd.errors());
    out.append('}');
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendErrors(StringBuilder out, boolean first, List<ProblemDetail.Entry> errors) {
    if (errors == null || errors.isEmpty()) {
      return;
    }
    if (!first) {
      out.append(',');
    }
    out.append("\"errors\":[");
    for (int i = 0; i < errors.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      ProblemDetail.Entry e = errors.get(i);
      out.append('{');
      boolean entryFirst = true;
      entryFirst = appendString(out, entryFirst, "pointer", e.pointer());
      entryFirst = appendString(out, entryFirst, "keyword", e.keyword());
      appendString(out, entryFirst, "detail", e.detail());
      out.append('}');
    }
    out.append(']');
  }

  private static boolean appendString(StringBuilder out, boolean first, String name, String value) {
    if (value == null) {
      return first;
    }
    if (!first) {
      out.append(',');
    }
    out.append('"').append(name).append("\":");
    JsonStrings.appendQuoted(out, value);
    return false;
  }

  private static boolean appendInt(StringBuilder out, boolean first, String name, int value) {
    if (!first) {
      out.append(',');
    }
    out.append('"').append(name).append("\":").append(value);
    return false;
  }
}
```

- [ ] **Step 5: Update `HandlersDefaultExceptionTest` assertions**

In `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java`, the two assertions that read top-level `pointer`/`keyword` must read from `errors[0]` instead.

Replace the body of `validationExceptionRendersProblemJson` (lines 44-60) — keep the setup, change the final assertions:

```java
  @Test
  void validationExceptionRendersProblemJson() {
    Response resp =
        Handlers.defaultExceptionHandler()
            .handle(
                new ValidationException(
                    new ValidationError("/x", "type", "expected string", null)));

    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    byte[] bytes = (byte[]) resp.body();
    String json = new String(bytes, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) JSON.readFrom(bytes, "application/json");
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(400);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) parsed.get("errors");
    assertThat(errors).singleElement().satisfies(entry -> {
      assertThat(entry).containsEntry("pointer", "#/x").containsEntry("keyword", "type");
    });
    assertThat(json).contains("expected string");
  }
```

Replace the final assertion block of `badRequestExceptionRendersProblemJsonWithCustomStatus` (lines 73-78) — keep setup through the `status` assertion, then:

```java
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(422);
    assertThat(parsed)
        .containsEntry("title", "Unprocessable Content")
        .containsEntry("detail", "email taken");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) parsed.get("errors");
    assertThat(errors).singleElement().satisfies(entry -> {
      assertThat(entry).containsEntry("pointer", "#/email").containsEntry("keyword", "unique");
    });
```

Add `import java.util.List;` to the file's imports if not present (it currently imports `java.util.Map` and `java.util.Set`).

- [ ] **Step 6: Create `ProblemDetailTest` for ordering / de-dup / fragment**

Create `src/test/java/com/retailsvc/http/internal/ProblemDetailTest.java`:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.internal.ProblemDetail.Entry;
import com.retailsvc.http.validate.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailTest {

  @Test
  void leafErrorBecomesSingleFragmentEntry() {
    var pd = ProblemDetail.forValidation(new ValidationError("/age", "type", "expected integer", "x"));
    assertThat(pd.errors())
        .containsExactly(new Entry("#/age", "type", "expected integer"));
  }

  @Test
  void rootPointerFragmentsToHash() {
    var pd = ProblemDetail.forValidation(new ValidationError("", "type", "expected object", 1));
    assertThat(pd.errors()).containsExactly(new Entry("#", "type", "expected object"));
  }

  @Test
  void branchesSortedDeepestPointerFirst() {
    var deep = new ValidationError("/pet/collar/size", "type", "expected integer", "big");
    var shallow = new ValidationError("/pet/bark", "type", "expected boolean", 7);
    var combinator =
        new ValidationError(
            "/pet", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(shallow, deep));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/pet/collar/size", "type", "expected integer"),
            new Entry("#/pet/bark", "type", "expected boolean"));
  }

  @Test
  void identicalBranchErrorsAreDeduplicated() {
    var a = new ValidationError("/kind", "required", "required property missing", null);
    var b = new ValidationError("/kind", "required", "required property missing", null);
    var combinator =
        new ValidationError("", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(a, b));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(new Entry("#/kind", "required", "required property missing"));
  }

  @Test
  void equalDepthBranchesKeepSchemaOrder() {
    var first = new ValidationError("/radius", "required", "required property missing", null);
    var second = new ValidationError("/kind", "enum", "value not in enum", "triangle");
    var combinator =
        new ValidationError(
            "", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(first, second));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/radius", "required", "required property missing"),
            new Entry("#/kind", "enum", "value not in enum"));
  }

  @Test
  void nestedCombinatorBranchSurfacesAsSingleSummaryEntry() {
    // A branch that is itself a failed combinator contributes ONE entry carrying its own
    // summary; its sub-branches are not recursively expanded (Decision 7). The hidden sub-leaf
    // is deep (/pet/reward/x/y/z) but does not surface, so the nested entry sorts by its own
    // shallow pointer (/pet/reward, depth 2), behind the genuinely deeper sibling leaf (depth 3).
    var hiddenSubLeaf = new ValidationError("/pet/reward/x/y/z", "type", "expected number", "s");
    var nestedCombinator =
        new ValidationError(
            "/pet/reward", "oneOf", "matched 0 of 3 oneOf branches", null, List.of(hiddenSubLeaf));
    var deeperLeaf = new ValidationError("/pet/collar/size", "type", "expected integer", "big");
    var top =
        new ValidationError(
            "/pet", "oneOf", "matched 0 of 2 oneOf branches", null,
            List.of(nestedCombinator, deeperLeaf));

    var pd = ProblemDetail.forValidation(top);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/pet/collar/size", "type", "expected integer"),
            new Entry("#/pet/reward", "oneOf", "matched 0 of 3 oneOf branches"));
  }

  @Test
  void badRequestWithoutPointerHasEmptyErrors() {
    var pd = ProblemDetail.forBadRequest(new BadRequestException("nope"));
    assertThat(pd.errors()).isEmpty();
  }

  @Test
  void badRequestWithPointerBecomesSingleEntry() {
    var pd = ProblemDetail.forBadRequest(new BadRequestException(409, "taken", "/email", "unique"));
    assertThat(pd.errors()).containsExactly(new Entry("#/email", "unique", "taken"));
  }
}
```

- [ ] **Step 7: Run the affected suites to verify they pass**

Run: `mvn -q test -Dtest=ProblemDetailRendererTest,ProblemDetailTest,HandlersDefaultExceptionTest`
Expected: PASS (all).

- [ ] **Step 8: Run the full unit-test build to confirm green**

Run: `mvn -q test`
Expected: `BUILD SUCCESS`, 0 failures. (`OpenApiServerIT` is a Failsafe `*IT` test and does not run under `mvn test`.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ProblemDetail.java \
        src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java \
        src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java \
        src/test/java/com/retailsvc/http/internal/ProblemDetailTest.java \
        src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java
git commit -m "feat: Move validation pointer and keyword into an errors array

Each failure is now an entry in an errors[] array with a #/...
JSON-Pointer-in-fragment pointer. oneOf/anyOf failures list one entry
per branch, deepest-first and de-duplicated."
```

---

### Task 4: End-to-end assertions for `errors[]` (multi-entry + de-dup)

Strengthens the existing `/shapes` (`oneOf`) integration tests to assert the new wire shape over a real HTTP round-trip — including the de-dup case (`{"radius":2.5}` makes both branches fail identically at `/kind required`). Additive; nothing else changes.

**Files:**
- Test: `src/test/java/com/retailsvc/http/OpenApiServerIT.java:426-468` (extend two tests)

- [ ] **Step 1: Add `errors[]` assertions to the two `/shapes` failure tests**

In `src/test/java/com/retailsvc/http/OpenApiServerIT.java`, in `postShapeUnknownKindReturns400` (body `{"kind":"triangle","side":3}`), after the existing `assertThat(response.body()).contains("oneOf");` line (line 439), add:

```java
        // Both branches fail at distinct leaves -> two entries, in the errors[] array.
        assertThat(response.body()).contains("\"errors\"").contains("#/radius").contains("#/kind");
```

In `postShapeMissingDiscriminatorReturns400` (body `{"radius":2.5}`), after the existing `assertThat(response.body()).contains("oneOf");` line (line 461), add:

```java
        // Both branches fail identically at /kind required -> de-duplicated to one entry.
        assertThat(response.body()).contains("\"errors\"").contains("#/kind");
        assertThat(response.body().split("#/kind", -1)).hasSize(2); // exactly one occurrence
```

- [ ] **Step 2: Run the integration test**

Run: `mvn -q verify -Dit.test=OpenApiServerIT -DfailIfNoTests=false`
Expected: PASS — the `/shapes` tests show `errors[]` with the expected pointers, and the de-dup body yields exactly one `#/kind` occurrence.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/OpenApiServerIT.java
git commit -m "test: Assert errors array end-to-end for oneOf failures"
```

---

### Task 5: Document the `errors[]` shape (README)

Updates the "Error responses" section to RFC 9457 and the `errors[]` array, replacing the flat `pointer`/`keyword` documentation.

**Files:**
- Modify: `README.md` (section "Error responses", lines ~878-919; plus the four scattered `RFC 7807` mentions at lines ~13, 30, 50, 633, 873)

- [ ] **Step 1: Rewrite the "Error responses" section**

In `README.md`, replace the section heading and body from line 878 (`## Error responses (RFC 7807)`) through the example code block (ending line 907) with:

````markdown
## Error responses (RFC 9457)

Validation failures — missing required fields, type mismatches, unsupported content types,
coercion errors, malformed bodies — produce an `HTTP 400 Bad Request` response with body media
type `application/problem+json`, following
[RFC 9457](https://datatracker.ietf.org/doc/html/rfc9457) (which obsoletes RFC 7807).

The top level carries the RFC core members; each individual failure is an entry in an `errors`
array (an RFC 9457 extension member). A non-combinator failure yields a single entry; a
`oneOf` / `anyOf` failure yields one entry per failed branch, ordered most-likely-cause first
(the branch the payload most resembles) and de-duplicated.

| Field      | Type    | Description                                                                              |
| ---------- | ------- | ---------------------------------------------------------------------------------------- |
| `type`     | string  | Always `about:blank` (no per-error type URI).                                            |
| `title`    | string  | Always `Bad Request`.                                                                    |
| `status`   | integer | Always `400`.                                                                            |
| `detail`   | string  | Human-readable description (a leaf message, or `matched 0 of N oneOf branches` for a combinator). |
| `errors`   | array   | One entry per failure; omitted when empty. Each entry has the fields below.              |

Each `errors[]` entry:

| Field      | Type    | Description                                                                              |
| ---------- | ------- | ---------------------------------------------------------------------------------------- |
| `pointer`  | string  | [RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) JSON-Pointer to the failing location, as a URI fragment — e.g. `#/age` for a body field, `#/query/limit` / `#/path/id` for parameters, `#/body` for whole-body errors (missing body, unsupported content type), or `#` when the entire body is the wrong type. |
| `keyword`  | string  | The validation rule that failed: `type`, `required`, `enum`, `pattern`, `format`, `minimum`, `maximum`, `minLength`, `maxLength`, `additionalProperties`, `oneOf`, `anyOf`, `allOf`, `not`, `const`, `content-type`, `decode`, … |
| `detail`   | string  | Human-readable description of this failure (e.g. `expected integer`).                    |

Example body for `POST /form-echo` with `age=abc` (`age` is declared as `integer`):

``` json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "expected integer",
  "errors": [
    { "pointer": "#/age", "keyword": "type", "detail": "expected integer" }
  ]
}
```

Example body for a `oneOf` request body that matches no branch — one entry per branch,
deepest (most-likely) first:

``` json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "matched 0 of 2 oneOf branches",
  "errors": [
    { "pointer": "#/offers/0/conditions/0/itemSet/minQuantity", "keyword": "type", "detail": "expected number" }
  ]
}
```
````

- [ ] **Step 2: Update the scattered `RFC 7807` references and the table-of-contents anchor**

Make these exact replacements in `README.md`:

- Line ~13: `(de)serialisation, and RFC 7807 error rendering.` → `(de)serialisation, and RFC 9457 error rendering.`
- Line ~30 (table of contents): `- [Error responses (RFC 7807)](#error-responses-rfc-7807)` → `- [Error responses (RFC 9457)](#error-responses-rfc-9457)`
- Line ~50: `- RFC 7807 \`application/problem+json\` validation errors with JSON-Pointer to the failing location` → `- RFC 9457 \`application/problem+json\` validation errors with an \`errors[]\` array of JSON-Pointers to the failing locations`
- Line ~633: `\`SchemeValidator\` callback, and renders RFC 7807 \`application/problem+json\` rejections — 401 for` → `\`SchemeValidator\` callback, and renders RFC 9457 \`application/problem+json\` rejections — 401 for`
- Line ~873: `Coercion failures surface as RFC-7807 \`400\` responses with a JSON-pointer to the failing field.` → `Coercion failures surface as RFC-9457 \`400\` responses with a JSON-pointer to the failing field.`

(Use `grep -n "7807" README.md` to confirm none remain except inside the parenthetical "(which obsoletes RFC 7807)" you added in Step 1.)

- [ ] **Step 3: Verify no stale references and the anchor is consistent**

Run: `grep -n "7807\|error-responses" README.md`
Expected: the only `7807` is the "obsoletes RFC 7807" note; the TOC anchor `#error-responses-rfc-9457` matches the new heading `## Error responses (RFC 9457)`.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: Document errors array response shape (RFC 9457)"
```

---

### Task 6: Final verification

- [ ] **Step 1: Full build (unit + integration + coverage)**

Run: `mvn -q verify`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors. This runs Surefire (all unit tests incl. `ValidationErrorTest`, `DefaultValidatorDispatchTest`, `ProblemDetailTest`, `ProblemDetailRendererTest`, `HandlersDefaultExceptionTest`, `ValidatorNoThrowOnHappyPathTest`) and Failsafe (`OpenApiServerIT`).

- [ ] **Step 2: Eyeball the new wire shape against the motivating case**

Confirm the design's worked example is reflected: a `oneOf` body failing inside one branch now returns `errors[]` with a `#/…` pointer at the real failing leaf, deepest-first and de-duplicated, while the top-level `detail` keeps the `matched 0 of N` summary. The `/shapes` IT assertions (Task 4) are the automated proxy for this.

- [ ] **Step 3: Confirm clean tree**

Run: `git status -sb`
Expected: clean working tree; all changes committed across Tasks 1-5.
