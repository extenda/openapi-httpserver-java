# Non-JSON Request Bodies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Accept `application/x-www-form-urlencoded` and `text/plain` request bodies (Wave 2 / orig #15, Slice A), with type coercion for form-body fields.

**Architecture:** Inside `RequestPreparationFilter.validateAndParseBody`, dispatch on the request's `Content-Type` subtype. Three branches: JSON (existing `JsonMapper`), form-urlencoded (new built-in parser + coercion), text/plain (new built-in parser). Parsing logic lives in focused new classes under `internal/`; the existing `coerceParameterValue` is hoisted into a shared `ValueCoercion` helper used by both parameter coercion and form-body coercion.

**Tech Stack:** Java 25, `com.sun.net.httpserver`, JDK stdlib `URLDecoder` / `Charset`. JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/superpowers/specs/2026-05-13-non-json-request-bodies-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/retailsvc/http/internal/ValueCoercion.java` — hoisted helper. Coerce a single string to integer / number / boolean per `Schema`. Public static `coerce(String raw, Schema schema, String pointer)`.
- `src/main/java/com/retailsvc/http/internal/ContentTypeHeader.java` — parse `Content-Type` header: `subtype(header)` returns bare media-type; `parameter(header, name)` returns named parameter (charset, etc.).
- `src/main/java/com/retailsvc/http/internal/TextPlainParser.java` — decode `byte[]` to `String` using charset from header (default UTF-8).
- `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java` — parse `byte[]` to `Map<String, Object>` (string or list-of-strings per repeated key), then coerce field values against an `ObjectSchema`.
- `src/test/java/com/retailsvc/http/internal/ValueCoercionTest.java`
- `src/test/java/com/retailsvc/http/internal/ContentTypeHeaderTest.java`
- `src/test/java/com/retailsvc/http/internal/TextPlainParserTest.java`
- `src/test/java/com/retailsvc/http/internal/FormUrlEncodedParserTest.java`
- `src/test/java/com/retailsvc/http/start/FormEchoHandler.java` — test handler that echoes the parsed form body as JSON.
- `src/test/java/com/retailsvc/http/start/TextEchoHandler.java` — test handler that echoes the parsed text body.
- `src/test/java/com/retailsvc/http/NonJsonBodyIT.java`

**Modify:**
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — replace inline `coerceParameterValue` with `ValueCoercion.coerce`; refactor `validateAndParseBody` to dispatch by content-type subtype; instantiate `FormUrlEncodedParser` / `TextPlainParser` in the constructor.
- `src/test/resources/openapi.json` — add `POST /form-echo` and `POST /text-echo` operations.
- `src/test/resources/openapi.yaml` — mirror the JSON additions (yaml-mirrors-json convention).
- `README.md` — short subsection on supported request body content types.

---

## Task 1: Hoist `ValueCoercion`

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ValueCoercion.java`
- Create: `src/test/java/com/retailsvc/http/internal/ValueCoercionTest.java`
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/retailsvc/http/internal/ValueCoercionTest.java`:

```java
package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValueCoercionTest {

  private final Schema intSchema =
      new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, null, null, Map.of());
  private final Schema numSchema =
      new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, null, null, Map.of());
  private final Schema boolSchema = new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of());
  private final Schema strSchema =
      new StringSchema(
          Set.of(TypeName.STRING), null, null, null, null, null, null, null, null, Map.of());

  @Test
  void coercesIntegerString() {
    assertThat(ValueCoercion.coerce("42", intSchema, "/a")).isEqualTo(42L);
  }

  @Test
  void coercesNumberString() {
    assertThat(ValueCoercion.coerce("3.14", numSchema, "/a")).isEqualTo(3.14);
  }

  @Test
  void coercesBooleanTrue() {
    assertThat(ValueCoercion.coerce("true", boolSchema, "/a")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void coercesBooleanFalse() {
    assertThat(ValueCoercion.coerce("false", boolSchema, "/a")).isEqualTo(Boolean.FALSE);
  }

  @Test
  void leavesStringSchemaUntouched() {
    assertThat(ValueCoercion.coerce("hello", strSchema, "/a")).isEqualTo("hello");
  }

  @Test
  void integerCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("abc", intSchema, "/a"))
        .isInstanceOf(ValidationException.class)
        .extracting("error.pointer", "error.keyword")
        .containsExactly("/a", "type");
    assertThat(HTTP_BAD_REQUEST).isEqualTo(400); // sanity: ValidationException is mapped to 400
  }

  @Test
  void numberCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("not-a-number", numSchema, "/x"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void booleanCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("yes", boolSchema, "/b"))
        .isInstanceOf(ValidationException.class);
  }
}
```

> If the constructor signatures for `IntegerSchema` / `NumberSchema` / `StringSchema` / `BooleanSchema` differ from what's shown, read the actual record headers in `src/main/java/com/retailsvc/http/spec/schema/` and adjust the fixtures. The intent is "default-valued schema of the given type" — copy the minimum form used elsewhere in tests (`grep -rn 'new IntegerSchema' src/test`).

- [ ] **Step 2: Run tests, expect compile failure**

Run: `mvn -q test -Dtest=ValueCoercionTest`
Expected: `cannot find symbol: ValueCoercion`.

- [ ] **Step 3: Create `ValueCoercion`**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.validate.ValidationError;

/** Coerces wire-format strings (parameters, form-field values) to the target schema type. */
public final class ValueCoercion {

  private ValueCoercion() {}

  public static Object coerce(String raw, Schema schema, String pointer) {
    return switch (schema) {
      case IntegerSchema _ -> {
        try {
          yield Long.parseLong(raw);
        } catch (NumberFormatException _) {
          throw new ValidationException(
              new ValidationError(pointer, "type", "expected integer", raw));
        }
      }
      case NumberSchema _ -> {
        try {
          yield Double.parseDouble(raw);
        } catch (NumberFormatException _) {
          throw new ValidationException(
              new ValidationError(pointer, "type", "expected number", raw));
        }
      }
      case BooleanSchema _ -> {
        if ("true".equals(raw)) {
          yield Boolean.TRUE;
        }
        if ("false".equals(raw)) {
          yield Boolean.FALSE;
        }
        throw new ValidationException(
            new ValidationError(pointer, "type", "expected boolean", raw));
      }
      default -> raw;
    };
  }
}
```

- [ ] **Step 4: Run tests, expect pass**

Run: `mvn -q test -Dtest=ValueCoercionTest`
Expected: 8 tests pass.

- [ ] **Step 5: Replace inline helper in `RequestPreparationFilter`**

In `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`:

1. Delete the private static method `coerceParameterValue(String raw, Schema schema, String pointer)` (currently around line 141–171).
2. Replace its single call site (around line 131) so:
```java
validator.validate(coerceParameterValue(value, p.schema(), pointer), p.schema(), pointer);
```
becomes:
```java
validator.validate(ValueCoercion.coerce(value, p.schema(), pointer), p.schema(), pointer);
```
3. Remove now-unused imports if any (`IntegerSchema`, `NumberSchema`, `BooleanSchema` — check whether they're still referenced anywhere in the file).

- [ ] **Step 6: Run full unit suite**

Run: `mvn -q test`
Expected: all existing tests still pass (the hoist is a no-op).

- [ ] **Step 7: Commit**

```
git add src/main/java/com/retailsvc/http/internal/ValueCoercion.java src/test/java/com/retailsvc/http/internal/ValueCoercionTest.java src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java
git commit -m "refactor: Hoist parameter coercion into ValueCoercion helper"
```

---

## Task 2: `ContentTypeHeader` helper

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ContentTypeHeader.java`
- Create: `src/test/java/com/retailsvc/http/internal/ContentTypeHeaderTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContentTypeHeaderTest {

  @Test
  void subtypeReturnsBareMediaType() {
    assertThat(ContentTypeHeader.subtype("application/json")).isEqualTo("application/json");
  }

  @Test
  void subtypeStripsParameters() {
    assertThat(ContentTypeHeader.subtype("text/plain; charset=utf-8")).isEqualTo("text/plain");
  }

  @Test
  void subtypeTrimsWhitespace() {
    assertThat(ContentTypeHeader.subtype("  application/json  ")).isEqualTo("application/json");
  }

  @Test
  void subtypeDefaultsToApplicationJsonWhenNull() {
    assertThat(ContentTypeHeader.subtype(null)).isEqualTo("application/json");
  }

  @Test
  void parameterReturnsValue() {
    assertThat(ContentTypeHeader.parameter("text/plain; charset=iso-8859-1", "charset"))
        .contains("iso-8859-1");
  }

  @Test
  void parameterUnquotesValue() {
    assertThat(ContentTypeHeader.parameter("text/plain; charset=\"utf-8\"", "charset"))
        .contains("utf-8");
  }

  @Test
  void parameterReturnsEmptyWhenMissing() {
    assertThat(ContentTypeHeader.parameter("text/plain", "charset")).isEmpty();
  }

  @Test
  void parameterNameMatchIsCaseInsensitive() {
    assertThat(ContentTypeHeader.parameter("text/plain; CHARSET=utf-8", "charset"))
        .contains("utf-8");
  }

  @Test
  void parameterReturnsEmptyForNullHeader() {
    assertThat(ContentTypeHeader.parameter(null, "charset")).isEmpty();
  }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q test -Dtest=ContentTypeHeaderTest`
Expected: `cannot find symbol: ContentTypeHeader`.

- [ ] **Step 3: Create the helper**

```java
package com.retailsvc.http.internal;

import java.util.Locale;
import java.util.Optional;

/** Parses {@code Content-Type} header values. */
public final class ContentTypeHeader {

  private ContentTypeHeader() {}

  /** Returns the bare media type, stripping parameters. {@code null} → {@code application/json}. */
  public static String subtype(String header) {
    if (header == null) {
      return "application/json";
    }
    int semi = header.indexOf(';');
    String bare = (semi < 0 ? header : header.substring(0, semi));
    return bare.trim();
  }

  /** Returns the named parameter value (e.g. {@code charset}), or empty if absent. */
  public static Optional<String> parameter(String header, String name) {
    if (header == null) {
      return Optional.empty();
    }
    String target = name.toLowerCase(Locale.ROOT);
    int semi = header.indexOf(';');
    if (semi < 0) {
      return Optional.empty();
    }
    String[] parts = header.substring(semi + 1).split(";");
    for (String p : parts) {
      String trimmed = p.trim();
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (!key.equals(target)) {
        continue;
      }
      String value = trimmed.substring(eq + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      return Optional.of(value);
    }
    return Optional.empty();
  }
}
```

- [ ] **Step 4: Verify pass**

Run: `mvn -q test -Dtest=ContentTypeHeaderTest`
Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/retailsvc/http/internal/ContentTypeHeader.java src/test/java/com/retailsvc/http/internal/ContentTypeHeaderTest.java
git commit -m "feat: Add ContentTypeHeader helper for parsing media-type headers"
```

---

## Task 3: `TextPlainParser`

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/TextPlainParser.java`
- Create: `src/test/java/com/retailsvc/http/internal/TextPlainParserTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextPlainParserTest {

  private final TextPlainParser parser = new TextPlainParser();

  @Test
  void decodesUtf8ByDefault() {
    String body = "hello världen";
    assertThat(parser.parse(body.getBytes(StandardCharsets.UTF_8), null)).isEqualTo(body);
  }

  @Test
  void respectsCharsetFromHeader() {
    String body = "räksmörgås";
    byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(bytes, "text/plain; charset=iso-8859-1")).isEqualTo(body);
  }

  @Test
  void emptyBodyDecodesToEmptyString() {
    assertThat(parser.parse(new byte[0], null)).isEqualTo("");
  }

  @Test
  void unknownCharsetFallsBackToUtf8() {
    String body = "hello";
    assertThat(parser.parse(body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=bogus"))
        .isEqualTo(body);
  }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q test -Dtest=TextPlainParserTest`
Expected: `cannot find symbol: TextPlainParser`.

- [ ] **Step 3: Create the parser**

```java
package com.retailsvc.http.internal;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/** Decodes a text/plain request body using the charset declared on {@code Content-Type}. */
public final class TextPlainParser {

  public String parse(byte[] body, String contentTypeHeader) {
    Charset charset = resolveCharset(contentTypeHeader);
    return new String(body, charset);
  }

  private static Charset resolveCharset(String header) {
    return ContentTypeHeader.parameter(header, "charset")
        .map(TextPlainParser::safeCharset)
        .orElse(StandardCharsets.UTF_8);
  }

  private static Charset safeCharset(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
      return StandardCharsets.UTF_8;
    }
  }
}
```

- [ ] **Step 4: Verify pass**

Run: `mvn -q test -Dtest=TextPlainParserTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/retailsvc/http/internal/TextPlainParser.java src/test/java/com/retailsvc/http/internal/TextPlainParserTest.java
git commit -m "feat: Add TextPlainParser for text/plain request bodies"
```

---

## Task 4: `FormUrlEncodedParser` — parsing only (no coercion yet)

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java`
- Create: `src/test/java/com/retailsvc/http/internal/FormUrlEncodedParserTest.java`

- [ ] **Step 1: Write failing parse-only tests**

Create the test class with these methods (we'll add coercion tests in Task 5):

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormUrlEncodedParserTest {

  private final FormUrlEncodedParser parser = new FormUrlEncodedParser();

  @Test
  void emptyBodyReturnsEmptyMap() {
    assertThat(parser.parse(new byte[0], null)).isEmpty();
  }

  @Test
  void singleField() {
    assertThat(parser.parse("a=1".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "1"));
  }

  @Test
  void multipleFields() {
    Map<String, Object> result = parser.parse("a=1&b=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
  }

  @Test
  void repeatedKeyBecomesList() {
    Map<String, Object> result = parser.parse("a=1&a=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", List.of("1", "2")));
  }

  @Test
  void threeRepeatedValues() {
    Map<String, Object> result =
        parser.parse("x=1&x=2&x=3".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("x", List.of("1", "2", "3")));
  }

  @Test
  void emptyValue() {
    assertThat(parser.parse("a=".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void keyWithoutEquals() {
    assertThat(parser.parse("a".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void percentDecodesKeyAndValue() {
    assertThat(parser.parse("a%20b=c%26d".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a b", "c&d"));
  }

  @Test
  void plusIsSpace() {
    assertThat(parser.parse("a=b+c".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "b c"));
  }

  @Test
  void charsetFromHeader() {
    byte[] iso = "x=räka".getBytes(StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(iso, "application/x-www-form-urlencoded; charset=iso-8859-1"))
        .containsExactly(Map.entry("x", "räka"));
  }
}
```

- [ ] **Step 2: Verify failure**

Run: `mvn -q test -Dtest=FormUrlEncodedParserTest`
Expected: `cannot find symbol: FormUrlEncodedParser`.

- [ ] **Step 3: Create the parser (no coercion yet)**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.spec.schema.Schema;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses an {@code application/x-www-form-urlencoded} request body. */
public final class FormUrlEncodedParser {

  /** Parses the body to a {@code Map<String, Object>} ({@code String} or {@code List<String>}). */
  public Map<String, Object> parse(byte[] body, String contentTypeHeader) {
    Charset charset = resolveCharset(contentTypeHeader);
    if (body.length == 0) {
      return new LinkedHashMap<>();
    }
    String text = new String(body, charset);
    Map<String, Object> out = new LinkedHashMap<>();
    for (String pair : text.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq < 0 ? pair : pair.substring(0, eq);
      String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
      String key = URLDecoder.decode(rawKey, charset);
      String value = URLDecoder.decode(rawValue, charset);
      addEntry(out, key, value);
    }
    return out;
  }

  private static void addEntry(Map<String, Object> out, String key, String value) {
    Object existing = out.get(key);
    if (existing == null) {
      out.put(key, value);
      return;
    }
    if (existing instanceof List<?> list) {
      @SuppressWarnings("unchecked")
      List<String> typed = (List<String>) list;
      typed.add(value);
      return;
    }
    List<String> list = new ArrayList<>();
    list.add((String) existing);
    list.add(value);
    out.put(key, list);
  }

  /** Returns the parsed map after coercing field values against the given body schema. */
  public Map<String, Object> parseAndCoerce(byte[] body, String contentTypeHeader, Schema schema) {
    // Coercion lives in the next task; for now this delegates to parse() so the
    // dispatch refactor in Task 6 can already call this method.
    return parse(body, contentTypeHeader);
  }

  private static Charset resolveCharset(String header) {
    return ContentTypeHeader.parameter(header, "charset")
        .map(FormUrlEncodedParser::safeCharset)
        .orElse(StandardCharsets.UTF_8);
  }

  private static Charset safeCharset(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
      return StandardCharsets.UTF_8;
    }
  }
}
```

- [ ] **Step 4: Verify pass**

Run: `mvn -q test -Dtest=FormUrlEncodedParserTest`
Expected: 10 tests pass.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java src/test/java/com/retailsvc/http/internal/FormUrlEncodedParserTest.java
git commit -m "feat: Add FormUrlEncodedParser (parsing only, no coercion yet)"
```

---

## Task 5: `FormUrlEncodedParser` — coercion against the body schema

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java`
- Modify: `src/test/java/com/retailsvc/http/internal/FormUrlEncodedParserTest.java`

- [ ] **Step 1: Append failing coercion tests**

Add to `FormUrlEncodedParserTest`:

```java
  @Test
  void coercesIntegerProperty() {
    com.retailsvc.http.spec.schema.IntegerSchema intSchema =
        new com.retailsvc.http.spec.schema.IntegerSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.INTEGER),
            null, null, null, null, null, null, null, java.util.Map.of());
    com.retailsvc.http.spec.schema.ObjectSchema bodySchema =
        new com.retailsvc.http.spec.schema.ObjectSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.OBJECT),
            java.util.Map.of("age", intSchema),
            java.util.List.of(),
            null,
            null,
            null,
            java.util.Map.of());

    Map<String, Object> out =
        parser.parseAndCoerce("age=30".getBytes(StandardCharsets.UTF_8), null, bodySchema);

    assertThat(out).containsExactly(Map.entry("age", 30L));
  }

  @Test
  void coercesArrayOfIntegersProperty() {
    com.retailsvc.http.spec.schema.IntegerSchema intItems =
        new com.retailsvc.http.spec.schema.IntegerSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.INTEGER),
            null, null, null, null, null, null, null, java.util.Map.of());
    com.retailsvc.http.spec.schema.ArraySchema arrSchema =
        new com.retailsvc.http.spec.schema.ArraySchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.ARRAY),
            intItems, null, null, false, java.util.Map.of());
    com.retailsvc.http.spec.schema.ObjectSchema bodySchema =
        new com.retailsvc.http.spec.schema.ObjectSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.OBJECT),
            java.util.Map.of("ids", arrSchema),
            java.util.List.of(), null, null, null, java.util.Map.of());

    Map<String, Object> out =
        parser.parseAndCoerce("ids=1&ids=2".getBytes(StandardCharsets.UTF_8), null, bodySchema);

    assertThat(out).containsExactly(Map.entry("ids", List.of(1L, 2L)));
  }

  @Test
  void coercionFailureThrowsValidationExceptionAtPropertyPointer() {
    com.retailsvc.http.spec.schema.IntegerSchema intSchema =
        new com.retailsvc.http.spec.schema.IntegerSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.INTEGER),
            null, null, null, null, null, null, null, java.util.Map.of());
    com.retailsvc.http.spec.schema.ObjectSchema bodySchema =
        new com.retailsvc.http.spec.schema.ObjectSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.OBJECT),
            java.util.Map.of("age", intSchema),
            java.util.List.of(), null, null, null, java.util.Map.of());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> parser.parseAndCoerce(
                "age=abc".getBytes(StandardCharsets.UTF_8), null, bodySchema))
        .isInstanceOf(com.retailsvc.http.ValidationException.class)
        .extracting("error.pointer", "error.keyword")
        .containsExactly("/age", "type");
  }

  @Test
  void unknownPropertyPassesThroughUnchanged() {
    com.retailsvc.http.spec.schema.ObjectSchema bodySchema =
        new com.retailsvc.http.spec.schema.ObjectSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.OBJECT),
            java.util.Map.of(),
            java.util.List.of(), null, null, null, java.util.Map.of());

    Map<String, Object> out =
        parser.parseAndCoerce("anything=v".getBytes(StandardCharsets.UTF_8), null, bodySchema);

    assertThat(out).containsExactly(Map.entry("anything", "v"));
  }

  @Test
  void nonObjectSchemaReturnsRawMap() {
    com.retailsvc.http.spec.schema.StringSchema strSchema =
        new com.retailsvc.http.spec.schema.StringSchema(
            java.util.Set.of(com.retailsvc.http.spec.schema.TypeName.STRING),
            null, null, null, null, null, null, null, null, java.util.Map.of());

    Map<String, Object> out =
        parser.parseAndCoerce("a=1".getBytes(StandardCharsets.UTF_8), null, strSchema);

    assertThat(out).containsExactly(Map.entry("a", "1"));
  }
```

> Cross-check `ObjectSchema` / `ArraySchema` / `IntegerSchema` / `StringSchema` constructor arity by reading their record headers before running. The constructors used here mirror `src/main/java/com/retailsvc/http/spec/schema/*.java`. If a fixture line fails to compile, adjust to match the actual record signatures.

- [ ] **Step 2: Verify failure**

Run: `mvn -q test -Dtest=FormUrlEncodedParserTest`
Expected: failures in the four new tests (coercion not yet wired through `parseAndCoerce`).

- [ ] **Step 3: Implement coercion in `parseAndCoerce`**

Replace the existing body of `parseAndCoerce` in `FormUrlEncodedParser.java`:

```java
  public Map<String, Object> parseAndCoerce(byte[] body, String contentTypeHeader, Schema schema) {
    Map<String, Object> parsed = parse(body, contentTypeHeader);
    if (!(schema instanceof com.retailsvc.http.spec.schema.ObjectSchema obj)) {
      return parsed;
    }
    Map<String, Schema> properties = obj.properties();
    for (Map.Entry<String, Object> e : parsed.entrySet()) {
      Schema propSchema = properties.get(e.getKey());
      if (propSchema == null) {
        continue;
      }
      String pointer = "/" + e.getKey();
      Object value = e.getValue();
      if (propSchema instanceof com.retailsvc.http.spec.schema.ArraySchema arr
          && value instanceof List<?> list) {
        List<Object> coerced = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          coerced.add(ValueCoercion.coerce((String) list.get(i), arr.items(), pointer + "/" + i));
        }
        e.setValue(coerced);
      } else if (propSchema instanceof com.retailsvc.http.spec.schema.ArraySchema arr
          && value instanceof String s) {
        // Single value but schema is array — coerce as a one-element list.
        e.setValue(List.of(ValueCoercion.coerce(s, arr.items(), pointer + "/0")));
      } else if (value instanceof String s) {
        e.setValue(ValueCoercion.coerce(s, propSchema, pointer));
      }
    }
    return parsed;
  }
```

(You can keep the explicit `com.retailsvc.http.spec.schema.*` qualifiers, or add imports — Google Java Format will reorder either way.)

- [ ] **Step 4: Verify pass**

Run: `mvn -q test -Dtest=FormUrlEncodedParserTest`
Expected: all 15 tests pass.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java src/test/java/com/retailsvc/http/internal/FormUrlEncodedParserTest.java
git commit -m "feat: Form body field coercion against ObjectSchema property types"
```

---

## Task 6: Wire dispatch into `RequestPreparationFilter`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`

- [ ] **Step 1: Add parsers as fields**

In `RequestPreparationFilter`, after the existing `private final JsonMapper jsonMapper;` field, add:

```java
  private final FormUrlEncodedParser formParser = new FormUrlEncodedParser();
  private final TextPlainParser textParser = new TextPlainParser();
```

- [ ] **Step 2: Refactor `validateAndParseBody` to dispatch**

Replace the existing body of `validateAndParseBody` (currently around lines 173–199):

```java
  private Object validateAndParseBody(HttpExchange exchange, Operation op, byte[] body) {
    Optional<RequestBody> rb = op.requestBody();
    if (rb.isEmpty()) {
      return null;
    }
    if (body.length == 0) {
      if (rb.get().required()) {
        throw new ValidationException(
            new ValidationError("/body", "required", "request body is required", null));
      }
      return null;
    }
    String header = exchange.getRequestHeaders().getFirst("Content-Type");
    String subtype = ContentTypeHeader.subtype(header);
    MediaType mt = rb.get().content().get(subtype);
    if (mt == null) {
      throw new ValidationException(
          new ValidationError(
              "/body", "content-type", "unsupported content type: " + subtype, null));
    }
    Object parsed =
        switch (subtype) {
          case "application/x-www-form-urlencoded" ->
              formParser.parseAndCoerce(body, header, mt.schema());
          case "text/plain" -> textParser.parse(body, header);
          default -> jsonMapper.mapFrom(body);
        };
    validator.validate(parsed, mt.schema(), "");
    return parsed;
  }
```

- [ ] **Step 3: Run full unit test suite**

Run: `mvn -q test`
Expected: all tests pass (the existing JSON path is unchanged for any content-type that isn't form or text/plain).

- [ ] **Step 4: Commit**

```
git add src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java
git commit -m "feat: Dispatch request body parsing by Content-Type subtype"
```

---

## Task 7: Test fixtures and echo handlers

**Files:**
- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/resources/openapi.yaml`
- Create: `src/test/java/com/retailsvc/http/start/FormEchoHandler.java`
- Create: `src/test/java/com/retailsvc/http/start/TextEchoHandler.java`

- [ ] **Step 1: Inspect existing fixtures**

Read both `src/test/resources/openapi.json` and `src/test/resources/openapi.yaml`. Note an existing JSON-body operation (e.g. `post-data`) and mirror its style for the new entries. The basePath is `/api/v1` and `paths` keys are written without that prefix.

- [ ] **Step 2: Append `POST /form-echo` to `openapi.json`**

Insert (preserving JSON validity — comma-separate from the previous path):

```json
    "/form-echo": {
      "post": {
        "operationId": "form-echo",
        "requestBody": {
          "required": true,
          "content": {
            "application/x-www-form-urlencoded": {
              "schema": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "age": { "type": "integer" },
                  "tags": {
                    "type": "array",
                    "items": { "type": "string" }
                  }
                }
              }
            }
          }
        },
        "responses": {
          "200": { "description": "ok" }
        }
      }
    },
    "/text-echo": {
      "post": {
        "operationId": "text-echo",
        "requestBody": {
          "required": true,
          "content": {
            "text/plain": {
              "schema": { "type": "string" }
            }
          }
        },
        "responses": {
          "200": { "description": "ok" }
        }
      }
    }
```

- [ ] **Step 3: Mirror in `openapi.yaml`**

Add the same two operations to `src/test/resources/openapi.yaml`. Use YAML syntax:

```yaml
  /form-echo:
    post:
      operationId: form-echo
      requestBody:
        required: true
        content:
          application/x-www-form-urlencoded:
            schema:
              type: object
              properties:
                name: { type: string }
                age: { type: integer }
                tags:
                  type: array
                  items: { type: string }
      responses:
        "200": { description: ok }
  /text-echo:
    post:
      operationId: text-echo
      requestBody:
        required: true
        content:
          text/plain:
            schema: { type: string }
      responses:
        "200": { description: ok }
```

- [ ] **Step 4: Create `FormEchoHandler`**

```java
package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Echoes the parsed form body to the response as a simple toString of the Map. */
public class FormEchoHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Object parsed = Request.parsed();
    byte[] body = String.valueOf(parsed).getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
```

- [ ] **Step 5: Create `TextEchoHandler`**

```java
package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Echoes the parsed text/plain body back to the response. */
public class TextEchoHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String parsed = (String) Request.parsed();
    byte[] body = parsed.getBytes(StandardCharsets.UTF_8);
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }
}
```

- [ ] **Step 6: Run full suite to confirm fixtures still parse**

Run: `mvn -q test`
Expected: BUILD SUCCESS. The new operations are not yet exercised, but the fixture must still parse cleanly for `ServerBaseTest` to load it.

- [ ] **Step 7: Commit**

```
git add src/test/resources/openapi.json src/test/resources/openapi.yaml src/test/java/com/retailsvc/http/start/FormEchoHandler.java src/test/java/com/retailsvc/http/start/TextEchoHandler.java
git commit -m "test: Fixtures and handlers for form-urlencoded and text/plain bodies"
```

---

## Task 8: Integration tests

**Files:**
- Create: `src/test/java/com/retailsvc/http/NonJsonBodyIT.java`

- [ ] **Step 1: Write failing IT**

Note: `ServerBaseTest.newRequest(...)` hard-codes `Content-Type: application/json` and `HttpRequest.Builder.header(...)` *adds* (does not replace) headers. To send a non-JSON `Content-Type`, build the request directly with `HttpRequest.newBuilder()` and target `http://localhost:<port>/api/v1/...` explicitly:

```java
package com.retailsvc.http;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.start.FormEchoHandler;
import com.retailsvc.http.start.TextEchoHandler;
import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NonJsonBodyIT extends ServerBaseTest {

  @Test
  void formUrlEncodedBodyParsedAndCoerced() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers); var client = httpClient()) {
      var req = postForm(s, "/form-echo", "name=foo&age=30");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      // FormEchoHandler renders Map#toString — Java prints e.g. {name=foo, age=30}
      assertThat(resp.body()).contains("name=foo").contains("age=30");
    }
  }

  @Test
  void formArrayProperty() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers); var client = httpClient()) {
      var req = postForm(s, "/form-echo", "tags=a&tags=b");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("tags=[a, b]");
    }
  }

  @Test
  void formCoercionFailureReturns400() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers); var client = httpClient()) {
      var req = postForm(s, "/form-echo", "age=abc");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(400);
      assertThat(resp.body()).contains("/age");
    }
  }

  @Test
  void textPlainBodyParsedAsString() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("text-echo", new TextEchoHandler());
    try (var s = newServer(handlers); var client = httpClient()) {
      var req = postWithContentType(s, "/text-echo", "hello", "text/plain; charset=utf-8");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).isEqualTo("hello");
    }
  }

  @Test
  void formBodyAgainstJsonOnlyOperationReturns400() throws Exception {
    // post-data operation in openapi.json declares only application/json.
    try (var s = newServer(Map.of()); var client = httpClient()) {
      var req = postForm(s, "/data", "name=foo");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(400);
      assertThat(resp.body()).contains("content-type");
    }
  }

  private static HttpRequest postForm(OpenApiServer s, String path, String body) {
    return postWithContentType(s, path, body, "application/x-www-form-urlencoded");
  }

  private static HttpRequest postWithContentType(
      OpenApiServer s, String path, String body, String contentType) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + s.listenPort() + "/api/v1" + path))
        .header("Content-Type", contentType)
        .POST(ofString(body))
        .build();
  }
}
```

- [ ] **Step 2: Run the new IT**

Run: `mvn -q verify -Dit.test=NonJsonBodyIT -DfailIfNoTests=false`
Expected: 5 tests pass.

- [ ] **Step 3: Full verify (regression)**

Run: `mvn -q verify`
Expected: all unit + integration tests pass.

- [ ] **Step 4: Commit**

```
git add src/test/java/com/retailsvc/http/NonJsonBodyIT.java
git commit -m "test: Integration coverage for form-urlencoded and text/plain bodies"
```

---

## Task 9: README update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Pick the insertion point**

Read README to find the existing usage / request-body section. The most natural location is right after the basic builder example, before the "Extra (non-OpenAPI) handlers" subsection.

- [ ] **Step 2: Add a "Request body content types" subsection**

Insert (use the README's heading level, typically `###`):

````markdown
### Request body content types

The server reads `requestBody.content` from the spec and selects a parser by `Content-Type` subtype:

| Content type                          | Parser                                   | Coercion |
| ------------------------------------- | ---------------------------------------- | -------- |
| `application/json`                    | Caller-supplied `JsonMapper`             | No — strict against the schema |
| `application/x-www-form-urlencoded`   | Built-in. `Map<String, Object>` (string or `List<String>` for repeated keys) | Yes — field values coerced to the property schema type (integer / number / boolean / array of those) |
| `text/plain`                          | Built-in. Decoded `String`               | No — schema should be `type: string` |

Form-field coercion mirrors the rules already used at the parameter boundary: wire is string-only by definition, so a property typed as `integer` accepts `"42"` and yields `42`. Coercion failures surface as RFC-7807 `400` responses with a JSON-pointer to the failing field.

Both built-in parsers honour the `charset=` parameter on the `Content-Type` header (default UTF-8). Unknown charsets fall back to UTF-8.
````

- [ ] **Step 3: Confirm pre-commit hooks pass on README**

Run: `pre-commit run --files README.md`
Expected: all hooks pass.

- [ ] **Step 4: Commit**

```
git add README.md
git commit -m "docs: Document form-urlencoded and text/plain request body support"
```

---

## Task 10: Final verification

- [ ] **Step 1: Clean build**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS. All unit and integration tests pass.

- [ ] **Step 2: Pre-commit on the tree**

Run: `pre-commit run --all-files`
Expected: all hooks pass.

- [ ] **Step 3: Working tree clean**

Run: `git status`
Expected: `nothing to commit, working tree clean`.
