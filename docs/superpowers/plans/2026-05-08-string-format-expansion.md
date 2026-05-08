# String Format Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenAPI 3.1 string `format` validation for `email`, `uri`, `uri-reference`, `hostname`, `ipv4`, `ipv6`, `regex`, `byte`, `binary`, `password` to `DefaultValidator`.

**Architecture:** Refactor the existing 3-arm `switch` on `format` in `DefaultValidator` into a static `Map<String, FormatCheck>` registry keyed by format name. Each entry holds a `Predicate<String>` plus a human-readable error message. Add the 10 new entries. No changes to `StringSchema` record shape, `Spec` parsing, or error rendering.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Maven (Surefire for unit tests, Failsafe for `*IT.java`).

**Spec:** `docs/superpowers/specs/2026-05-08-string-format-expansion-design.md`

**Conventions to honor:**
- Google Java Formatter (pre-commit auto-runs; never hand-format).
- Always use curly braces — no brace-less one-liners.
- Test method names: camelCase (e.g., `stringFormatEmail`), never `snake_case`.
- `openapi.json` and `openapi.yaml` test fixtures must mirror each other.
- Conventional Commits (commitlint enforces).
- No `Co-Authored-By` trailer.
- LSP diagnostics check after each edit; fix type errors immediately.

---

## File Structure

**Modify:**
- `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` — replace `validateStringFormat` switch with map-based dispatch; add 10 format entries.
- `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java` — add per-format unit tests next to the existing `stringFormatUuid` test.
- `src/test/resources/openapi.json` — add one operation exercising a new format (for the IT case).
- `src/test/resources/openapi.yaml` — mirror the JSON change.
- `src/test/java/com/retailsvc/http/OpenApiServerIT.java` — add an IT case for the new operation.

**No new files.**

---

## Task 1: Refactor existing format dispatch to a registry

Pure refactor. After this task, `mvn test` is fully green and `stringFormatUuid` still passes. No behavior change.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Verify baseline is green**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS, all tests pass including `stringFormatUuid`, `stringFormatDate` (if present).

- [ ] **Step 2: Add the registry and rewrite `validateStringFormat`**

Replace the existing `validateStringFormat` method body in `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` (currently ~lines 112–139) with the registry-driven version.

Add these imports if not already present:
```java
import java.util.function.Predicate;
```

Add these `private static final` members near the top of the class (after `FORMAT_KEYWORD`):

```java
private record FormatCheck(Predicate<String> isValid, String message) {}

private static final Map<String, FormatCheck> FORMAT_CHECKS =
    Map.of(
        "uuid", new FormatCheck(DefaultValidator::isUuid, "not a valid uuid"),
        "date", new FormatCheck(DefaultValidator::isDate, "not a valid date"),
        "date-time", new FormatCheck(DefaultValidator::isDateTime, "not a valid date-time"));
```

Replace `validateStringFormat` with:

```java
private void validateStringFormat(String str, String format, String pointer) {
  FormatCheck check = FORMAT_CHECKS.get(format);
  if (check == null) {
    return;
  }
  if (!check.isValid().test(str)) {
    fail(pointer, FORMAT_KEYWORD, check.message(), str);
  }
}
```

Add the three predicate helpers as `private static` methods on the class:

```java
private static boolean isUuid(String s) {
  try {
    UUID.fromString(s);
    return true;
  } catch (IllegalArgumentException _) {
    return false;
  }
}

private static boolean isDate(String s) {
  try {
    LocalDate.parse(s);
    return true;
  } catch (DateTimeParseException _) {
    return false;
  }
}

private static boolean isDateTime(String s) {
  try {
    OffsetDateTime.parse(s);
    return true;
  } catch (DateTimeParseException _) {
    return false;
  }
}
```

- [ ] **Step 3: Run pre-commit formatter via build, verify all tests still pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS, all tests pass.

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java
git commit -m "refactor: Replace string format switch with registry"
```

---

## Task 2: Add `email` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

Append after `stringFormatUuid` in `StringIntegerNumberTest.java`:

```java
@Test
void stringFormatEmail() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "email", null);
  assertThatCode(() -> v.validate("user@example.com", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("not-an-email", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("missing@dot", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatEmail`
Expected: FAIL — the second/third assertion fails because unknown format is silently ignored.

- [ ] **Step 3: Add the email pattern + registry entry**

In `DefaultValidator.java`, add a static field near the other constants:

```java
private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
```

Add to `FORMAT_CHECKS` map:

```java
"email", new FormatCheck(s -> EMAIL.matcher(s).matches(), "not a valid email"),
```

(Note: `Map.of` has a 10-entry limit. Once the map hits 11 entries, switch to `Map.ofEntries(Map.entry(...), ...)`. This first addition keeps it at 4 entries — fine. Subsequent tasks will switch.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatEmail`
Expected: PASS.

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS, all tests in the class pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'email'"
```

---

## Task 3: Add `uri` and `uri-reference` formats

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing tests**

Append to `StringIntegerNumberTest.java`:

```java
@Test
void stringFormatUri() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "uri", null);
  assertThatCode(() -> v.validate("https://example.com/path", s, "/v"))
      .doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("/relative/path", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("not a uri at all", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}

@Test
void stringFormatUriReference() {
  StringSchema s =
      new StringSchema(Set.of(TypeName.STRING), null, null, null, "uri-reference", null);
  assertThatCode(() -> v.validate("https://example.com", s, "/v"))
      .doesNotThrowAnyException();
  assertThatCode(() -> v.validate("/relative/path", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("ht tp://broken", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatUri+stringFormatUriReference`
Expected: FAIL.

- [ ] **Step 3: Add the predicates + registry entries**

In `DefaultValidator.java`, add the following imports if not present:

```java
import java.net.URI;
import java.net.URISyntaxException;
```

Add static helper methods on the class:

```java
private static boolean isUri(String s) {
  try {
    return new URI(s).isAbsolute();
  } catch (URISyntaxException _) {
    return false;
  }
}

private static boolean isUriReference(String s) {
  try {
    new URI(s);
    return true;
  } catch (URISyntaxException _) {
    return false;
  }
}
```

Note: use `new URI(s)` (throws `URISyntaxException`) rather than `URI.create(s)` (throws unchecked) so the catch is checked-exception-clean.

Convert `FORMAT_CHECKS` to `Map.ofEntries(...)` (the entry count is now 6, still under 10, but the next task will push past 10 — switching now keeps the diff smaller later). Final shape:

```java
private static final Map<String, FormatCheck> FORMAT_CHECKS =
    Map.ofEntries(
        Map.entry("uuid", new FormatCheck(DefaultValidator::isUuid, "not a valid uuid")),
        Map.entry("date", new FormatCheck(DefaultValidator::isDate, "not a valid date")),
        Map.entry(
            "date-time",
            new FormatCheck(DefaultValidator::isDateTime, "not a valid date-time")),
        Map.entry(
            "email",
            new FormatCheck(s -> EMAIL.matcher(s).matches(), "not a valid email")),
        Map.entry("uri", new FormatCheck(DefaultValidator::isUri, "not a valid uri")),
        Map.entry(
            "uri-reference",
            new FormatCheck(DefaultValidator::isUriReference, "not a valid uri-reference")));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string formats 'uri' and 'uri-reference'"
```

---

## Task 4: Add `hostname` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void stringFormatHostname() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "hostname", null);
  assertThatCode(() -> v.validate("example.com", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate("a.b.c.example", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("-leading-hyphen.com", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("invalid host name", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatHostname`
Expected: FAIL.

- [ ] **Step 3: Add the hostname pattern + registry entry**

Add static field:

```java
private static final Pattern HOSTNAME =
    Pattern.compile(
        "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)"
            + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");
```

Add to `FORMAT_CHECKS`:

```java
Map.entry(
    "hostname",
    new FormatCheck(s -> HOSTNAME.matcher(s).matches(), "not a valid hostname")),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'hostname'"
```

---

## Task 5: Add `ipv4` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void stringFormatIpv4() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "ipv4", null);
  assertThatCode(() -> v.validate("192.168.0.1", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate("0.0.0.0", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate("255.255.255.255", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("256.0.0.1", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("1.2.3", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatIpv4`
Expected: FAIL.

- [ ] **Step 3: Add the ipv4 pattern + registry entry**

```java
private static final Pattern IPV4 =
    Pattern.compile("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
```

Add to `FORMAT_CHECKS`:

```java
Map.entry("ipv4", new FormatCheck(s -> IPV4.matcher(s).matches(), "not a valid ipv4")),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'ipv4'"
```

---

## Task 6: Add `ipv6` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void stringFormatIpv6() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "ipv6", null);
  assertThatCode(() -> v.validate("2001:0db8:85a3:0000:0000:8a2e:0370:7334", s, "/v"))
      .doesNotThrowAnyException();
  assertThatCode(() -> v.validate("2001:db8::1", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate("::1", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("not:an:ipv6", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("12345::1", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatIpv6`
Expected: FAIL.

- [ ] **Step 3: Add the ipv6 pattern + registry entry**

The standard JSON Schema IPv6 regex (no IPv4 trailer; sufficient for OpenAPI use):

```java
private static final Pattern IPV6 =
    Pattern.compile(
        "^("
            + "([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,7}:"
            + "|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}"
            + "|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}"
            + "|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}"
            + "|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}"
            + "|[0-9a-fA-F]{1,4}:(:[0-9a-fA-F]{1,4}){1,6}"
            + "|:((:[0-9a-fA-F]{1,4}){1,7}|:)"
            + ")$");
```

Add to `FORMAT_CHECKS`:

```java
Map.entry("ipv6", new FormatCheck(s -> IPV6.matcher(s).matches(), "not a valid ipv6")),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

If `::1` fails: the regex variant is rejecting that case — tweak the final alternative to `|:(:[0-9a-fA-F]{1,4}){1,7}|::` and re-run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'ipv6'"
```

---

## Task 7: Add `regex` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void stringFormatRegex() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "regex", null);
  assertThatCode(() -> v.validate("^[a-z]+$", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("[unclosed", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("(?<bad", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatRegex`
Expected: FAIL.

- [ ] **Step 3: Add the predicate + registry entry**

Add import if not present:
```java
import java.util.regex.PatternSyntaxException;
```

Add static helper:

```java
private static boolean isRegex(String s) {
  try {
    Pattern.compile(s);
    return true;
  } catch (PatternSyntaxException _) {
    return false;
  }
}
```

Add to `FORMAT_CHECKS`:

```java
Map.entry("regex", new FormatCheck(DefaultValidator::isRegex, "not a valid regex")),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'regex'"
```

---

## Task 8: Add `byte` format

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void stringFormatByte() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "byte", null);
  assertThatCode(() -> v.validate("aGVsbG8=", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate("", s, "/v")).doesNotThrowAnyException();
  assertThatThrownBy(() -> v.validate("not base64!!", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
  assertThatThrownBy(() -> v.validate("a===", s, "/v"))
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("format");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatByte`
Expected: FAIL.

- [ ] **Step 3: Add the predicate + registry entry**

Add import:
```java
import java.util.Base64;
```

Add static helper:

```java
private static boolean isByte(String s) {
  try {
    Base64.getDecoder().decode(s);
    return true;
  } catch (IllegalArgumentException _) {
    return false;
  }
}
```

Add to `FORMAT_CHECKS`:

```java
Map.entry("byte", new FormatCheck(DefaultValidator::isByte, "not valid base64")),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Validate string format 'byte'"
```

---

## Task 9: Add `binary` and `password` no-op formats

These are recognized but always pass — locking in OpenAPI-spec semantics.

**Files:**
- Modify: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void stringFormatBinaryAcceptsAnyString() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "binary", null);
  assertThatCode(() -> v.validate("anything goes", s, "/v")).doesNotThrowAnyException();
  assertThatCode(() -> v.validate(" ", s, "/v")).doesNotThrowAnyException();
}

@Test
void stringFormatPasswordAcceptsAnyString() {
  StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "password", null);
  assertThatCode(() -> v.validate("anything goes", s, "/v")).doesNotThrowAnyException();
}
```

These pass even before the registry change (because unknown formats are silently ignored). The intent is to lock in the no-op semantics so a future change can't accidentally start asserting against `binary`/`password`.

- [ ] **Step 2: Run tests to verify they pass**

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatBinaryAcceptsAnyString+stringFormatPasswordAcceptsAnyString`
Expected: PASS.

- [ ] **Step 3: Add explicit registry entries**

Add to `FORMAT_CHECKS`:

```java
Map.entry("binary", new FormatCheck(s -> true, "not valid binary")),
Map.entry("password", new FormatCheck(s -> true, "not valid password")),
```

(The messages are unreachable but required by the record; they document the slot.)

- [ ] **Step 4: Re-run tests**

Run: `mvn test -Dtest=StringIntegerNumberTest`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat: Recognize 'binary' and 'password' string formats as no-ops"
```

---

## Task 10: Wire one format end-to-end through `OpenApiServer` (IT)

Add a small operation to the test fixtures and an integration test confirming a 400 + `application/problem+json` response when validation fails.

**Files:**
- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/resources/openapi.yaml`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java`

- [ ] **Step 1: Add two new operations to `openapi.json`**

Add two new path entries inside the `paths` object (alongside the existing operations). One exercises a regex-based format (`email`), the other a parsing-based format (`byte`):

```json
"/format/email": {
  "get": {
    "operationId": "format-email",
    "parameters": [
      {
        "in": "query",
        "name": "addr",
        "required": true,
        "schema": {
          "type": "string",
          "format": "email"
        }
      }
    ],
    "responses": {
      "200": {
        "description": "OK"
      }
    }
  }
},
"/format/byte": {
  "get": {
    "operationId": "format-byte",
    "parameters": [
      {
        "in": "query",
        "name": "data",
        "required": true,
        "schema": {
          "type": "string",
          "format": "byte"
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

Add the equivalent two blocks in `src/test/resources/openapi.yaml` so both fixtures describe the same API. Verify by inspection that both files now have a `format-email` operation at `/format/email` and a `format-byte` operation at `/format/byte` with matching parameter shapes.

- [ ] **Step 3: Write the failing IT case**

Find an existing IT case in `OpenApiServerIT.java` that returns 400 with `application/problem+json` (e.g., `getDataShouldReturnBadRequestOnInvalidXNameHeader` around line 57) and add a parallel test in the same nested class. Also register a handler for `format-email` in whatever fixture wires up handlers (look near the top of the IT for `registerHandler` or an equivalent map entry).

```java
@Test
void formatEmailShouldReturnBadRequestOnInvalidEmail() {
  try (var server = serverWithDefaultHandlers();
      var client = httpClient()) {
    var response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUri(server) + "/format/email?addr=not-an-email"))
                .GET()
                .build(),
            BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("content-type").orElseThrow())
        .contains("application/problem+json");
    assertThat(response.body()).contains("\"format\"");
  }
}

@Test
void formatEmailShouldReturnOkOnValidEmail() {
  try (var server = serverWithDefaultHandlers();
      var client = httpClient()) {
    var response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUri(server) + "/format/email?addr=user%40example.com"))
                .GET()
                .build(),
            BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }
}

@Test
void formatByteShouldReturnBadRequestOnInvalidBase64() {
  try (var server = serverWithDefaultHandlers();
      var client = httpClient()) {
    var response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUri(server) + "/format/byte?data=not%20base64!!"))
                .GET()
                .build(),
            BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("content-type").orElseThrow())
        .contains("application/problem+json");
    assertThat(response.body()).contains("\"format\"");
  }
}

@Test
void formatByteShouldReturnOkOnValidBase64() {
  try (var server = serverWithDefaultHandlers();
      var client = httpClient()) {
    var response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUri(server) + "/format/byte?data=aGVsbG8%3D"))
                .GET()
                .build(),
            BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }
}
```

Adjust the `serverWithDefaultHandlers()` / `baseUri(server)` calls to match this file's existing helpers — copy the surrounding pattern of an adjacent test exactly. Add a handler registration for `format-email` that returns 200 with empty body, mirroring how other simple handlers in this fixture are registered.

- [ ] **Step 4: Run the IT to verify it fails**

Run: `mvn verify -Dit.test=OpenApiServerIT#formatEmailShouldReturnBadRequestOnInvalidEmail+formatEmailShouldReturnOkOnValidEmail -DfailIfNoTests=false`
Expected: FAIL — likely "no handler for operation format-email" or similar, until handler is registered. If failure is "404 not found", confirm both fixture files were updated and re-run.

- [ ] **Step 5: Register the `format-email` and `format-byte` handlers**

In `OpenApiServerIT.java`, find where the test server's handler map is built (search for an existing operationId like `get-data` to locate the registration site). Add two handlers that simply return 200 with no body:

```java
.handler("format-email", exchange -> {
  exchange.sendResponseHeaders(200, -1);
})
.handler("format-byte", exchange -> {
  exchange.sendResponseHeaders(200, -1);
})
```

Use whatever `.handler(...)` / `Map.of(...)` / builder pattern the file already uses. Match style exactly.

- [ ] **Step 6: Run the IT to verify it passes**

Run: `mvn verify -Dit.test=OpenApiServerIT -DfailIfNoTests=false`
Expected: BUILD SUCCESS, all IT cases pass including the two new ones.

- [ ] **Step 7: Run the full build**

Run: `mvn verify`
Expected: BUILD SUCCESS, all unit + IT tests pass, JaCoCo report generated.

- [ ] **Step 8: Commit**

```bash
git add src/test/resources/openapi.json src/test/resources/openapi.yaml src/test/java/com/retailsvc/http/OpenApiServerIT.java
git commit -m "test: Verify string format validation end-to-end via OpenApiServer"
```

---

## Task 11: Final verification

- [ ] **Step 1: Confirm full build is clean**

Run: `mvn verify`
Expected: BUILD SUCCESS. No test failures. No skipped tests beyond the usual.

- [ ] **Step 2: Sanity-check the format registry has all 13 entries**

Open `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` and confirm `FORMAT_CHECKS` contains exactly these keys: `uuid`, `date`, `date-time`, `email`, `uri`, `uri-reference`, `hostname`, `ipv4`, `ipv6`, `regex`, `byte`, `binary`, `password`.

- [ ] **Step 3: Sanity-check that an unknown format is still ignored**

Add a one-off test (then revert / keep, your call) — the design requires unknown formats to remain silently ignored:

```java
@Test
void stringFormatUnknownIsIgnored() {
  StringSchema s =
      new StringSchema(Set.of(TypeName.STRING), null, null, null, "definitely-not-a-format", null);
  assertThatCode(() -> v.validate("anything", s, "/v")).doesNotThrowAnyException();
}
```

Run: `mvn test -Dtest=StringIntegerNumberTest#stringFormatUnknownIsIgnored`
Expected: PASS. Keep this test — it locks in the contract for unknown formats. Commit:

```bash
git add src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "test: Lock in 'unknown format silently ignored' contract"
```

- [ ] **Step 4: Final `mvn verify` and push the branch**

Run: `mvn verify`
Expected: BUILD SUCCESS.

Push the branch (per repo memory: gh CLI cannot create PRs here; user opens it manually):

```bash
git push -u origin HEAD
```

Notify the user the branch is pushed and ready for them to open the PR.
