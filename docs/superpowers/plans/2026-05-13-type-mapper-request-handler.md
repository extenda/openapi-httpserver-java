# TypeMapper and RequestHandler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `JsonMapper` with a per-media-type `TypeMapper` (read + write) registered on the builder, with an optional auto-fallback to a Gson-backed default for `application/json`. Replace handler API `Map<String, HttpHandler>` (which receives `HttpExchange`) with `Map<String, RequestHandler>` receiving a `Request` per-request handle that owns both the read API and a fluent response gateway (one-shot + streaming).

**Architecture:** Spec at `docs/superpowers/specs/2026-05-13-type-mapper-request-handler-design.md`. Pre-1.0; breaking API changes accepted; single PR cutover. TDD throughout, frequent commits. Each task ends with `mvn test` (or `mvn verify` for IT) green.

**Tech Stack:** Java 25, `com.sun.net.httpserver.HttpServer`, JUnit 5, AssertJ, Gson (becomes optional Maven dependency).

---

## File Structure

**Will be created:**

- `src/main/java/com/retailsvc/http/TypeMapper.java` — new public interface.
- `src/main/java/com/retailsvc/http/RequestHandler.java` — new public functional interface.
- `src/main/java/com/retailsvc/http/ResponseBuilder.java` — new public interface returned by `Request.respond(int)`.
- `src/main/java/com/retailsvc/http/internal/FormTypeMapper.java` — built-in `application/x-www-form-urlencoded` `TypeMapper`.
- `src/main/java/com/retailsvc/http/internal/TextTypeMapper.java` — built-in `text/plain` `TypeMapper`.
- `src/main/java/com/retailsvc/http/internal/FormBodyCoercion.java` — schema-aware coercion extracted from `FormUrlEncodedParser`.
- `src/main/java/com/retailsvc/http/internal/DefaultResponseBuilder.java` — concrete `ResponseBuilder` wrapping `HttpExchange`.
- `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java` — built-in Gson-backed `TypeMapper` for `application/json`.
- `src/test/java/com/retailsvc/http/TypeMapperRegistrationTest.java`
- `src/test/java/com/retailsvc/http/RequestResponseGatewayTest.java`
- `src/test/java/com/retailsvc/http/internal/FormTypeMapperTest.java`
- `src/test/java/com/retailsvc/http/internal/TextTypeMapperTest.java`
- `src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java`

**Will be modified:**

- `pom.xml` — move Gson from test scope to compile scope with `<optional>true</optional>`.
- `src/main/java/com/retailsvc/http/Request.java` — rewritten from static-accessor utility to per-request handle.
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — constructors and `Builder` updated (`jsonMapper(...)` removed, `bodyMapper(...)` added, `handlers(...)` takes `Map<String, RequestHandler>`).
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — dispatch via `Map<String, TypeMapper>`; construct `Request`; bind to internal `ScopedValue<Request>`.
- `src/main/java/com/retailsvc/http/internal/DispatchHandler.java` — reads `Request` from new `ScopedValue<Request>`, calls `RequestHandler`.
- `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java` — drop `parseAndCoerce`; keep `parse` only.
- `src/test/java/com/retailsvc/http/ServerBaseTest.java` — switch to `bodyMapper(...)` + `RequestHandler`.
- `src/test/java/com/retailsvc/http/start/ServerLauncher.java`, `start/PostDataHandler.java`, etc. — migrate to new APIs.
- `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`, `OpenApiServerTest.java`, `ExtraHandlersIT.java`, `internal/RequestPreparationFilterTest.java` — adjust to new APIs.
- `README.md` — document `bodyMapper(...)`, Gson fallback + write caveat, new `RequestHandler` shape.

**Will be deleted:**

- `src/main/java/com/retailsvc/http/JsonMapper.java`
- `src/main/java/com/retailsvc/http/internal/RequestContext.java`
- `src/test/java/com/retailsvc/http/JsonMapperTest.java`

---

## Task 1: Extract form-body schema coercion out of `FormUrlEncodedParser`

`FormUrlEncodedParser.parseAndCoerce(byte[], String, Schema)` mixes parsing with schema-aware coercion. To make the form parser fit `TypeMapper` (which has no `Schema` parameter), move coercion into a small internal helper and call it from `RequestPreparationFilter` for the form media type.

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/FormBodyCoercion.java`
- Modify: `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java` (remove `parseAndCoerce`)
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` (line 155-156: invoke coercion after parsing)

- [ ] **Step 1: Run the existing form-body tests so we know the current baseline is green.**

Run: `mvn test -Dtest='*FormUrlEncodedParser*,*RequestPreparationFilter*' -q`
Expected: all pass.

- [ ] **Step 2: Create `FormBodyCoercion` with the coercion loop lifted verbatim from `FormUrlEncodedParser.parseAndCoerce`.**

Create `src/main/java/com/retailsvc/http/internal/FormBodyCoercion.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coerces string-typed values produced by {@link FormUrlEncodedParser} into the Java types described
 * by the body schema (numbers, booleans, arrays). Called by {@link RequestPreparationFilter} after
 * parsing, before validation.
 */
final class FormBodyCoercion {

  private FormBodyCoercion() {}

  static Map<String, Object> coerce(Map<String, Object> parsed, Schema schema) {
    if (!(schema instanceof ObjectSchema obj)) {
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
      if (propSchema instanceof ArraySchema arr && value instanceof List<?> list) {
        List<Object> coerced = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          coerced.add(ValueCoercion.coerce((String) list.get(i), arr.items(), pointer + "/" + i));
        }
        e.setValue(coerced);
      } else if (propSchema instanceof ArraySchema arr && value instanceof String s) {
        e.setValue(List.of(ValueCoercion.coerce(s, arr.items(), pointer + "/0")));
      } else if (value instanceof String s) {
        e.setValue(ValueCoercion.coerce(s, propSchema, pointer));
      }
    }
    return parsed;
  }
}
```

- [ ] **Step 3: Remove `parseAndCoerce` from `FormUrlEncodedParser`.**

Edit `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java`: delete the `parseAndCoerce` method and all imports that become unused (`ArraySchema`, `ObjectSchema`, `Schema`, `ValueCoercion`, `ArrayList`). `parse(byte[], String)` stays.

- [ ] **Step 4: Update `RequestPreparationFilter.validateAndParseBody` to call the new helper.**

At `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java:153-159`, replace the `switch` arm for form with two steps:

```java
Object parsed =
    switch (mediaType) {
      case "application/x-www-form-urlencoded" ->
          FormBodyCoercion.coerce(formParser.parse(body, header), mt.schema());
      case "text/plain" -> textParser.parse(body, header);
      default -> jsonMapper.mapFrom(body);
    };
```

- [ ] **Step 5: Run tests; everything still passes.**

Run: `mvn test -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/com/retailsvc/http/internal/FormBodyCoercion.java \
        src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java \
        src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java
git commit -m "refactor: Extract form-body schema coercion from FormUrlEncodedParser"
```

---

## Task 2: Introduce the `TypeMapper` interface

A small, isolated step: introduce the new public interface with no consumers yet so subsequent tasks can implement it.

**Files:**
- Create: `src/main/java/com/retailsvc/http/TypeMapper.java`

- [ ] **Step 1: Write a compile-only test that asserts the interface exists with the expected shape.**

Create `src/test/java/com/retailsvc/http/TypeMapperShapeTest.java`:

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TypeMapperShapeTest {

  @Test
  void roundTripsViaInlineImplementation() {
    TypeMapper identity =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] body, String contentTypeHeader) {
            return new String(body, StandardCharsets.UTF_8);
          }

          @Override
          public byte[] writeTo(Object value) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
          }
        };

    Object read = identity.readFrom("hi".getBytes(StandardCharsets.UTF_8), "text/plain");
    assertThat(read).isEqualTo("hi");
    assertThat(identity.writeTo("hi")).containsExactly('h', 'i');
  }
}
```

- [ ] **Step 2: Run the test; it fails because `TypeMapper` does not yet exist.**

Run: `mvn test -Dtest=TypeMapperShapeTest -q`
Expected: compile error / "cannot find symbol TypeMapper".

- [ ] **Step 3: Create the interface.**

Create `src/main/java/com/retailsvc/http/TypeMapper.java`:

```java
package com.retailsvc.http;

/**
 * Reads and writes request/response bodies for a specific media type. Registered on {@link
 * OpenApiServer.Builder#bodyMapper(String, TypeMapper)} keyed by media type. The library ships
 * built-in mappers for {@code application/x-www-form-urlencoded} and {@code text/plain}; an
 * {@code application/json} mapper must be supplied by the caller or auto-detected via Gson on the
 * classpath.
 */
public interface TypeMapper {

  /**
   * @param body raw request body bytes
   * @param contentTypeHeader the full raw {@code Content-Type} header, used for charset and other
   *     parameters (the JSON mapper ignores it)
   */
  Object readFrom(byte[] body, String contentTypeHeader);

  /** Serializes {@code value} to bytes suitable for writing as the response body. */
  byte[] writeTo(Object value);
}
```

- [ ] **Step 4: Run the test; it passes.**

Run: `mvn test -Dtest=TypeMapperShapeTest -q`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/retailsvc/http/TypeMapper.java \
        src/test/java/com/retailsvc/http/TypeMapperShapeTest.java
git commit -m "feat: Add TypeMapper interface"
```

---

## Task 3: Built-in `FormTypeMapper` and `TextTypeMapper`

These wrap the existing `FormUrlEncodedParser` and `TextPlainParser` and add a `writeTo` implementation. Form's `writeTo` throws `UnsupportedOperationException` (per spec); text's encodes `String.valueOf(value)` to UTF-8.

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/FormTypeMapper.java`
- Create: `src/main/java/com/retailsvc/http/internal/TextTypeMapper.java`
- Create: `src/test/java/com/retailsvc/http/internal/FormTypeMapperTest.java`
- Create: `src/test/java/com/retailsvc/http/internal/TextTypeMapperTest.java`

- [ ] **Step 1: Failing test for `FormTypeMapper`.**

Create `src/test/java/com/retailsvc/http/internal/FormTypeMapperTest.java`:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormTypeMapperTest {

  private final FormTypeMapper mapper = new FormTypeMapper();

  @Test
  void readsKeyValuePairs() {
    byte[] body = "name=Alice&color=blue".getBytes(StandardCharsets.UTF_8);
    Object parsed = mapper.readFrom(body, "application/x-www-form-urlencoded");
    assertThat(parsed).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) parsed;
    assertThat(m).containsEntry("name", "Alice").containsEntry("color", "blue");
  }

  @Test
  void writeToIsUnsupported() {
    assertThatThrownBy(() -> mapper.writeTo(Map.of("k", "v")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
```

Run: `mvn test -Dtest=FormTypeMapperTest -q`
Expected: compile error (no FormTypeMapper yet).

- [ ] **Step 2: Implement `FormTypeMapper`.**

Create `src/main/java/com/retailsvc/http/internal/FormTypeMapper.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.TypeMapper;

/**
 * Built-in {@link TypeMapper} for {@code application/x-www-form-urlencoded}. Reads delegate to
 * {@link FormUrlEncodedParser}. Writes are not supported — form-encoded responses are unusual and
 * intentionally left out until a real need surfaces.
 */
public final class FormTypeMapper implements TypeMapper {

  private final FormUrlEncodedParser parser = new FormUrlEncodedParser();

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return parser.parse(body, contentTypeHeader);
  }

  @Override
  public byte[] writeTo(Object value) {
    throw new UnsupportedOperationException(
        "application/x-www-form-urlencoded write is not supported; register a custom TypeMapper");
  }
}
```

- [ ] **Step 3: Verify form test passes.**

Run: `mvn test -Dtest=FormTypeMapperTest -q`
Expected: PASS.

- [ ] **Step 4: Failing test for `TextTypeMapper`.**

Create `src/test/java/com/retailsvc/http/internal/TextTypeMapperTest.java`:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextTypeMapperTest {

  private final TextTypeMapper mapper = new TextTypeMapper();

  @Test
  void readsUtf8ByDefault() {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    assertThat(mapper.readFrom(body, "text/plain")).isEqualTo("hello");
  }

  @Test
  void readsExplicitCharset() {
    byte[] body = "räksmörgås".getBytes(StandardCharsets.ISO_8859_1);
    assertThat(mapper.readFrom(body, "text/plain; charset=ISO-8859-1")).isEqualTo("räksmörgås");
  }

  @Test
  void writesStringValueAsUtf8() {
    assertThat(mapper.writeTo("ok")).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    assertThat(mapper.writeTo(42)).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
    assertThat(mapper.writeTo(null)).isEqualTo("null".getBytes(StandardCharsets.UTF_8));
  }
}
```

- [ ] **Step 5: Implement `TextTypeMapper`.**

Create `src/main/java/com/retailsvc/http/internal/TextTypeMapper.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.TypeMapper;
import java.nio.charset.StandardCharsets;

/**
 * Built-in {@link TypeMapper} for {@code text/plain}. Reads decode bytes using the charset declared
 * on {@code Content-Type} (default UTF-8). Writes return {@code String.valueOf(value)} encoded as
 * UTF-8.
 */
public final class TextTypeMapper implements TypeMapper {

  private final TextPlainParser parser = new TextPlainParser();

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return parser.parse(body, contentTypeHeader);
  }

  @Override
  public byte[] writeTo(Object value) {
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 6: Run tests; both pass.**

Run: `mvn test -Dtest='FormTypeMapperTest,TextTypeMapperTest' -q`
Expected: PASS.

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/com/retailsvc/http/internal/FormTypeMapper.java \
        src/main/java/com/retailsvc/http/internal/TextTypeMapper.java \
        src/test/java/com/retailsvc/http/internal/FormTypeMapperTest.java \
        src/test/java/com/retailsvc/http/internal/TextTypeMapperTest.java
git commit -m "feat: Built-in FormTypeMapper and TextTypeMapper"
```

---

## Task 4: Promote Gson to optional compile dependency

Today Gson is in test scope. Move it to compile scope with `<optional>true</optional>` so:
- the library can ship `GsonJsonMapper`;
- downstream consumers do not pull Gson transitively (so Jackson users are unaffected).

**Files:**
- Modify: `pom.xml` lines 60-64.

- [ ] **Step 1: Edit the Gson dependency.**

Replace the existing Gson `<dependency>` block in `pom.xml` (lines 60-64) with:

```xml
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.14.0</version>
      <optional>true</optional>
    </dependency>
```

- [ ] **Step 2: Sort the POM (the sortpom plugin runs at `validate` and will fail the build otherwise).**

Run: `mvn sortpom:sort -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the full unit test suite — Gson is still on the test classpath (optional deps are still pulled in for the declaring module's tests), so nothing should break.**

Run: `mvn test -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git add pom.xml
git commit -m "build: Make Gson an optional compile dependency"
```

---

## Task 5: `GsonJsonMapper` with integer-preserving and JSR-310 adapters

A library-owned `TypeMapper` for `application/json`, backed by Gson, never instantiated unless the builder's classpath probe finds Gson at runtime. Custom `TypeAdapter<Object>` for integer preservation; per-type write adapters for `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, `LocalDate`, `LocalTime`.

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java`
- Create: `src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java`

- [ ] **Step 1: Failing test covering read (integer preservation, fractional double, basic types) and write (JSR-310 ISO-8601 emission).**

Create `src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java`:

```java
package com.retailsvc.http.internal.gson;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GsonJsonMapperTest {

  private final GsonJsonMapper mapper = new GsonJsonMapper();

  @Test
  void readPreservesIntegersAsLong() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) mapper.readFrom(bytes("{\"n\":42}"), "application/json");
    assertThat(parsed.get("n")).isEqualTo(42L).isInstanceOf(Long.class);
  }

  @Test
  void readKeepsFractionalAsDouble() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) mapper.readFrom(bytes("{\"n\":1.5}"), "application/json");
    assertThat(parsed.get("n")).isEqualTo(1.5).isInstanceOf(Double.class);
  }

  @Test
  void readBasicTypes() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>)
            mapper.readFrom(
                bytes("{\"s\":\"hi\",\"b\":true,\"n\":null,\"a\":[1,2]}"),
                "application/json");
    assertThat(parsed.get("s")).isEqualTo("hi");
    assertThat(parsed.get("b")).isEqualTo(Boolean.TRUE);
    assertThat(parsed.get("n")).isNull();
    assertThat(parsed.get("a")).isEqualTo(List.of(1L, 2L));
  }

  @Test
  void writesMapAndList() {
    byte[] out = mapper.writeTo(Map.of("k", List.of(1L, 2L)));
    assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("{\"k\":[1,2]}");
  }

  @Test
  void writesInstantAsIso8601() {
    Instant t = Instant.parse("2026-05-13T10:00:00Z");
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00:00Z\"}");
  }

  @Test
  void writesOffsetDateTimeAsIso8601() {
    OffsetDateTime t = OffsetDateTime.of(2026, 5, 13, 10, 0, 0, 0, ZoneOffset.UTC);
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00Z\"}");
  }

  @Test
  void writesZonedDateTimeAsIso8601() {
    ZonedDateTime t = ZonedDateTime.of(2026, 5, 13, 10, 0, 0, 0, ZoneOffset.UTC);
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .contains("2026-05-13T10:00Z");
  }

  @Test
  void writesLocalDateTimeAsIso8601() {
    assertThat(
            new String(
                mapper.writeTo(Map.of("ts", LocalDateTime.of(2026, 5, 13, 10, 0))),
                StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00\"}");
  }

  @Test
  void writesLocalDateAsIso8601() {
    assertThat(
            new String(
                mapper.writeTo(Map.of("d", LocalDate.of(2026, 5, 13))), StandardCharsets.UTF_8))
        .isEqualTo("{\"d\":\"2026-05-13\"}");
  }

  @Test
  void writesLocalTimeAsIso8601() {
    assertThat(
            new String(mapper.writeTo(Map.of("t", LocalTime.of(10, 0))), StandardCharsets.UTF_8))
        .isEqualTo("{\"t\":\"10:00\"}");
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
```

Run: `mvn test -Dtest=GsonJsonMapperTest -q`
Expected: compile error (no GsonJsonMapper yet).

- [ ] **Step 2: Implement `GsonJsonMapper`.**

Create `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java`:

```java
package com.retailsvc.http.internal.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.retailsvc.http.TypeMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in {@link TypeMapper} for {@code application/json} backed by Gson. Auto-registered by
 * {@link com.retailsvc.http.OpenApiServer.Builder} when Gson is on the classpath and no
 * user-supplied JSON mapper has been registered.
 *
 * <p>The default {@code Object} {@link TypeAdapter} is replaced with one that returns {@code Long}
 * for integral JSON numbers and {@code Double} for fractional numbers, so the library's integer
 * schemas validate as expected. JSR-310 types ({@code Instant}, {@code OffsetDateTime},
 * {@code ZonedDateTime}, {@code LocalDateTime}, {@code LocalDate}, {@code LocalTime}) are written
 * as their ISO-8601 string form.
 */
public final class GsonJsonMapper implements TypeMapper {

  private final Gson gson;

  public GsonJsonMapper() {
    this.gson =
        new GsonBuilder()
            .registerTypeAdapter(Object.class, new IntegerPreservingObjectAdapter())
            .registerTypeAdapter(Instant.class, isoStringWriter(Instant::toString))
            .registerTypeAdapter(OffsetDateTime.class, isoStringWriter(OffsetDateTime::toString))
            .registerTypeAdapter(ZonedDateTime.class, isoStringWriter(ZonedDateTime::toString))
            .registerTypeAdapter(LocalDateTime.class, isoStringWriter(LocalDateTime::toString))
            .registerTypeAdapter(LocalDate.class, isoStringWriter(LocalDate::toString))
            .registerTypeAdapter(LocalTime.class, isoStringWriter(LocalTime::toString))
            .create();
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return gson.fromJson(new String(body, StandardCharsets.UTF_8), Object.class);
  }

  @Override
  public byte[] writeTo(Object value) {
    return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
  }

  private static <T> TypeAdapter<T> isoStringWriter(java.util.function.Function<T, String> toIso) {
    return new TypeAdapter<T>() {
      @Override
      public void write(JsonWriter out, T value) throws IOException {
        if (value == null) {
          out.nullValue();
        } else {
          out.value(toIso.apply(value));
        }
      }

      @Override
      public T read(JsonReader in) {
        throw new UnsupportedOperationException(
            "GsonJsonMapper does not parse JSR-310 types; values arrive as String");
      }
    };
  }

  private static final class IntegerPreservingObjectAdapter extends TypeAdapter<Object> {

    @Override
    public Object read(JsonReader in) throws IOException {
      JsonToken token = in.peek();
      switch (token) {
        case BEGIN_ARRAY -> {
          List<Object> list = new ArrayList<>();
          in.beginArray();
          while (in.hasNext()) {
            list.add(read(in));
          }
          in.endArray();
          return list;
        }
        case BEGIN_OBJECT -> {
          Map<String, Object> map = new LinkedHashMap<>();
          in.beginObject();
          while (in.hasNext()) {
            map.put(in.nextName(), read(in));
          }
          in.endObject();
          return map;
        }
        case STRING -> {
          return in.nextString();
        }
        case NUMBER -> {
          String raw = in.nextString();
          if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
              return Long.parseLong(raw);
            } catch (NumberFormatException _) {
              // falls through to Double for out-of-range integers
            }
          }
          return Double.parseDouble(raw);
        }
        case BOOLEAN -> {
          return in.nextBoolean();
        }
        case NULL -> {
          in.nextNull();
          return null;
        }
        default -> throw new IllegalStateException("Unexpected token: " + token);
      }
    }

    @Override
    public void write(JsonWriter out, Object value) throws IOException {
      // Delegate to Gson's default Object serialization by writing values manually.
      if (value == null) {
        out.nullValue();
      } else if (value instanceof Map<?, ?> map) {
        out.beginObject();
        for (Map.Entry<?, ?> e : map.entrySet()) {
          out.name(String.valueOf(e.getKey()));
          write(out, e.getValue());
        }
        out.endObject();
      } else if (value instanceof Iterable<?> it) {
        out.beginArray();
        for (Object e : it) {
          write(out, e);
        }
        out.endArray();
      } else if (value instanceof Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
          out.value(n.longValue());
        } else {
          out.value(n.doubleValue());
        }
      } else if (value instanceof Boolean b) {
        out.value(b);
      } else if (value instanceof String s) {
        out.value(s);
      } else {
        // Fall back to Gson default for unknown types (JSR-310 adapters take priority).
        out.value(value.toString());
      }
    }
  }
}
```

- [ ] **Step 3: Run the test; it passes.**

Run: `mvn test -Dtest=GsonJsonMapperTest -q`
Expected: PASS (all assertions).

- [ ] **Step 4: Commit.**

```bash
git add src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java \
        src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java
git commit -m "feat: GsonJsonMapper with integer-preserving and JSR-310 adapters"
```

---

## Task 6: Builder switch to `bodyMapper(...)`; delete `JsonMapper`; rewire filter

The atomic API cutover for the read pipeline:

1. `OpenApiServer.Builder.bodyMapper(String, TypeMapper)` replaces `jsonMapper(JsonMapper)`.
2. Builder wires defaults for form + text; probes `com.google.gson.Gson` and registers `GsonJsonMapper` if absent.
3. `RequestPreparationFilter` dispatches via `Map<String, TypeMapper>`.
4. `JsonMapper` and `JsonMapperTest` are deleted.
5. `OpenApiServer` constructors drop the `JsonMapper` parameter.
6. Test base, builder test, and ServerLauncher updated.

Compile breaks mid-task; we fix everything in one commit. Handler API (`Map<String, HttpHandler>`) is **not** touched here — that comes in Task 9.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` (constructors + builder fields + builder methods + `build()`)
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` (constructor signature + `validateAndParseBody` dispatch)
- Delete: `src/main/java/com/retailsvc/http/JsonMapper.java`
- Delete: `src/test/java/com/retailsvc/http/JsonMapperTest.java`
- Modify: `src/test/java/com/retailsvc/http/ServerBaseTest.java`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`
- Modify: `src/test/java/com/retailsvc/http/start/ServerLauncher.java`
- Create: `src/test/java/com/retailsvc/http/TypeMapperRegistrationTest.java`

- [ ] **Step 1: Write the registration tests (RED).**

Create `src/test/java/com/retailsvc/http/TypeMapperRegistrationTest.java`:

```java
package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeMapperRegistrationTest extends ServerBaseTest {

  @Test
  void gsonFallbackIsAutoRegisteredWhenNoJsonMapperConfigured() throws Exception {
    HttpHandler echo =
        ex -> {
          Object parsed = Request.parsed();
          byte[] out = gson.toJson(parsed).getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        };
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getRoot", echo, "postData", echo))
            .port(0)
            .build();
    HttpClient client =
        HttpClient.newBuilder()
            .executor(newVirtualThreadPerTaskExecutor())
            .version(HTTP_1_1)
            .build();
    var resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"n\":42}"))
                .build(),
            ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).contains("\"n\":42");
  }

  @Test
  void userSuppliedMapperOverridesDefault() throws Exception {
    TypeMapper marker = new TypeMapper() {
      @Override public Object readFrom(byte[] b, String h) { return Map.of("from", "custom"); }
      @Override public byte[] writeTo(Object v) { return "ignored".getBytes(StandardCharsets.UTF_8); }
    };
    HttpHandler echo =
        ex -> {
          ex.sendResponseHeaders(200, -1);
          ex.close();
        };
    OpenApiServer s =
        OpenApiServer.builder()
            .spec(spec)
            .bodyMapper("application/json", marker)
            .handlers(Map.of("getRoot", echo, "postData", echo))
            .port(0)
            .build();
    s.close();
  }

  @Test
  void bodyMapperRejectsNullArgs() {
    var b = OpenApiServer.builder();
    assertThatThrownBy(() -> b.bodyMapper(null, new GsonOnlyMapper()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> b.bodyMapper("application/json", null))
        .isInstanceOf(NullPointerException.class);
  }

  private static final class GsonOnlyMapper implements TypeMapper {
    @Override public Object readFrom(byte[] b, String h) { return null; }
    @Override public byte[] writeTo(Object v) { return new byte[0]; }
  }
}
```

Run: `mvn test -Dtest=TypeMapperRegistrationTest -q`
Expected: compile error (no `bodyMapper` method, no `Builder.spec`, etc.).

- [ ] **Step 2: Update `RequestPreparationFilter` to take `Map<String, TypeMapper>`.**

In `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`:

- Remove the import of `com.retailsvc.http.JsonMapper`.
- Add import of `com.retailsvc.http.TypeMapper`.
- Replace fields:

```java
private final Spec spec;
private final Router router;
private final Validator validator;
private final Map<String, TypeMapper> bodyMappers;
```

- Replace constructor:

```java
public RequestPreparationFilter(
    Spec spec, Router router, Validator validator, Map<String, TypeMapper> bodyMappers) {
  this.spec = spec;
  this.router = router;
  this.validator = validator;
  this.bodyMappers = Map.copyOf(bodyMappers);
}
```

- Delete the `formParser` and `textParser` fields (their behaviour moves into the registered mappers).
- Replace `validateAndParseBody`'s switch body:

```java
TypeMapper mapper = bodyMappers.get(mediaType);
if (mapper == null) {
  throw new ValidationException(
      new ValidationError(
          "/body", "content-type", "unsupported content type: " + mediaType, null));
}
Object parsed = mapper.readFrom(body, header);
if (mediaType.equals("application/x-www-form-urlencoded") && parsed instanceof Map<?, ?> map) {
  @SuppressWarnings("unchecked")
  Map<String, Object> typed = (Map<String, Object>) map;
  parsed = FormBodyCoercion.coerce(typed, mt.schema());
}
validator.validate(parsed, mt.schema(), "");
return parsed;
```

Note: the "unsupported content type" check now happens after the spec content-type check; both kinds of mismatch produce the same `ValidationException` shape. The existing `MediaType mt = rb.get().content().get(mediaType);` block immediately above stays — keep both as defence in depth.

- [ ] **Step 3: Rewrite `OpenApiServer` and its `Builder`.**

In `src/main/java/com/retailsvc/http/OpenApiServer.java`:

- Replace the `JsonMapper jsonMapper` constructor parameter (3 constructors) with `Map<String, TypeMapper> bodyMappers`.
- The public 4-arg and 5-arg constructors are removed; only the package-private full constructor remains. Add a public 1-arg / 2-arg pair for direct construction if needed (the test base only uses the builder, so keep the surface minimal — see Step 5).

Replace the entire file content with:

```java
package com.retailsvc.http;

import static java.lang.Thread.ofVirtual;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.internal.ExceptionFilter;
import com.retailsvc.http.internal.FormTypeMapper;
import com.retailsvc.http.internal.RequestPreparationFilter;
import com.retailsvc.http.internal.Router;
import com.retailsvc.http.internal.TextTypeMapper;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenApiServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApiServer.class);
  private static final int DEFAULT_PORT = 8080;
  private static final String JSON = "application/json";
  private static final String GSON_CLASS = "com.google.gson.Gson";
  private static final String GSON_MAPPER_CLASS =
      "com.retailsvc.http.internal.gson.GsonJsonMapper";

  private final HttpServer httpServer;
  private final int shutdownTimeoutSeconds;

  OpenApiServer(
      Spec spec,
      Map<String, TypeMapper> bodyMappers,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler,
      int port,
      Map<String, HttpHandler> extras,
      int shutdownTimeoutSeconds)
      throws IOException {

    requireNonNull(spec, "Spec must not be null");
    requireNonNull(bodyMappers, "bodyMappers must not be null");
    requireNonNull(handlers, "handlers must not be null");
    if (exceptionHandler == null) {
      LOG.warn("No ExceptionHandler set, using default");
      exceptionHandler = Handlers.defaultExceptionHandler();
    }

    long t0 = System.currentTimeMillis();
    Router router = new Router(spec.operations());
    DefaultValidator validator = new DefaultValidator(spec::resolveSchema);

    this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpServer.setExecutor(newThreadPerTaskExecutor(ofVirtual().name("http-", 0).factory()));

    HttpContext ctx = httpServer.createContext(Optional.ofNullable(spec.basePath()).orElse("/"));
    ctx.getFilters().add(new ExceptionFilter(exceptionHandler));
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, bodyMappers));
    ctx.setHandler(new DispatchHandler(handlers));

    for (Map.Entry<String, HttpHandler> e : extras.entrySet()) {
      HttpContext extraCtx = httpServer.createContext(e.getKey());
      extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
      extraCtx.setHandler(e.getValue());
    }

    httpServer.createContext("/", Handlers.notFoundHandler());
    httpServer.start();

    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

    LOG.info("Server started (port {}) in {}ms", port, System.currentTimeMillis() - t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  public void stop(int delaySeconds) {
    if (delaySeconds < 0) {
      throw new IllegalArgumentException("delaySeconds must be non-negative, got " + delaySeconds);
    }
    if (httpServer != null) {
      httpServer.stop(delaySeconds);
    }
  }

  @Override
  public void close() {
    stop(shutdownTimeoutSeconds);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private Spec spec;
    private final LinkedHashMap<String, TypeMapper> bodyMappers = new LinkedHashMap<>();
    private Map<String, HttpHandler> handlers;
    private ExceptionHandler exceptionHandler;
    private int port = DEFAULT_PORT;
    private int shutdownTimeoutSeconds = 0;
    private final LinkedHashMap<String, HttpHandler> extras = new LinkedHashMap<>();

    private Builder() {}

    public Builder spec(Spec spec) {
      this.spec = spec;
      return this;
    }

    public Builder bodyMapper(String mediaType, TypeMapper mapper) {
      requireNonNull(mediaType, "mediaType must not be null");
      requireNonNull(mapper, "mapper must not be null");
      bodyMappers.put(mediaType.toLowerCase(java.util.Locale.ROOT), mapper);
      return this;
    }

    public Builder handlers(Map<String, HttpHandler> handlers) {
      this.handlers = handlers;
      return this;
    }

    public Builder exceptionHandler(ExceptionHandler exceptionHandler) {
      this.exceptionHandler = exceptionHandler;
      return this;
    }

    public Builder port(int port) {
      this.port = port;
      return this;
    }

    public Builder shutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
      if (shutdownTimeoutSeconds < 0) {
        throw new IllegalArgumentException(
            "shutdownTimeoutSeconds must be non-negative, got " + shutdownTimeoutSeconds);
      }
      this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
      return this;
    }

    public Builder addHandler(String path, HttpHandler handler) {
      requireNonNull(path, "path must not be null");
      requireNonNull(handler, "handler must not be null");
      if (extras.containsKey(path)) {
        throw new IllegalStateException("duplicate extra handler path: " + path);
      }
      extras.put(path, handler);
      return this;
    }

    public OpenApiServer build() throws IOException {
      requireNonNull(spec, "Spec must not be null");
      requireNonNull(handlers, "handlers must not be null");
      String basePath = Optional.ofNullable(spec.basePath()).orElse("/");
      for (String path : extras.keySet()) {
        if (path.equals(basePath)) {
          throw new IllegalStateException(
              "extra handler path " + path + " conflicts with spec basePath " + basePath);
        }
      }
      Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
      return new OpenApiServer(
          spec, resolved, handlers, exceptionHandler, port, extras, shutdownTimeoutSeconds);
    }

    private static Map<String, TypeMapper> resolveBodyMappers(
        Map<String, TypeMapper> userSupplied) {
      LinkedHashMap<String, TypeMapper> out = new LinkedHashMap<>();
      out.put("application/x-www-form-urlencoded", new FormTypeMapper());
      out.put("text/plain", new TextTypeMapper());
      out.putAll(userSupplied);
      if (!out.containsKey(JSON)) {
        TypeMapper fallback = tryLoadGsonMapper();
        if (fallback != null) {
          out.put(JSON, fallback);
        }
      }
      if (!out.containsKey(JSON)) {
        throw new IllegalStateException(
            "No TypeMapper registered for application/json and Gson not found on classpath; "
                + "register one via Builder.bodyMapper(\"application/json\", ...)");
      }
      return out;
    }

    private static TypeMapper tryLoadGsonMapper() {
      try {
        Class.forName(GSON_CLASS, false, OpenApiServer.class.getClassLoader());
      } catch (ClassNotFoundException _) {
        return null;
      }
      try {
        Class<?> cls =
            Class.forName(GSON_MAPPER_CLASS, true, OpenApiServer.class.getClassLoader());
        return (TypeMapper) cls.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Failed to load " + GSON_MAPPER_CLASS, e);
      }
    }
  }
}
```

- [ ] **Step 4: Delete `JsonMapper.java` and `JsonMapperTest.java`.**

```bash
git rm src/main/java/com/retailsvc/http/JsonMapper.java src/test/java/com/retailsvc/http/JsonMapperTest.java
```

- [ ] **Step 5: Update `ServerBaseTest` to use the builder.**

In `src/test/java/com/retailsvc/http/ServerBaseTest.java`:

- Remove the `jsonMapper()` method.
- Replace `newServer(...)`:

```java
protected OpenApiServer newServer(Map<String, HttpHandler> handlers) {
  try {
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(handlers)
            .port(0)
            .build();
    return server;
  } catch (Exception e) {
    fail(e);
  }
  return null;
}
```

The Gson fallback covers the `application/json` mapper (Gson is on the test classpath).

- [ ] **Step 6: Update `ServerLauncher` and `OpenApiServerBuilderTest`.**

In `src/test/java/com/retailsvc/http/start/ServerLauncher.java`:

- Remove the import of `com.retailsvc.http.JsonMapper`.
- Remove `import com.google.gson.Gson;` if no longer needed (the Gson fallback handles it).
- Replace lines around 29-45 that build the `JsonMapper` and pass it to `OpenApiServer.builder()`. The launcher should simply call `.spec(spec).handlers(handlers).build()` and rely on the Gson fallback. (Concrete content depends on the current launcher shape; the rule is: no `JsonMapper` reference, no explicit JSON mapper registration.)

In `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`: find each call to `.jsonMapper(...)` and replace with `.bodyMapper("application/json", ...)`. Any test that explicitly verified `jsonMapper(null)` throws moves under `bodyMapper(...)` null checks. If a test asserted on `JsonMapper`'s type, delete it.

- [ ] **Step 7: Run the full unit suite.**

Run: `mvn test -q`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Run integration tests.**

Run: `mvn verify -q`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit.**

```bash
git add -u
git add src/test/java/com/retailsvc/http/TypeMapperRegistrationTest.java
git commit -m "feat!: Replace JsonMapper with bodyMapper(mediaType, TypeMapper) + Gson fallback"
```

---

## Task 7: Move legacy `Request` static accessors out of the way

Before introducing the new `Request` (per-request handle), rename the existing static-accessor class so the new class can take the canonical name without ambiguity during the cutover.

**Files:**
- Rename: `src/main/java/com/retailsvc/http/Request.java` → `src/main/java/com/retailsvc/http/internal/LegacyRequestAccess.java` (move to `internal` package; rename class).
- Modify: every reference: filter, dispatcher, tests, launcher.

- [ ] **Step 1: Move the file with `git mv` and rename the class.**

```bash
git mv src/main/java/com/retailsvc/http/Request.java src/main/java/com/retailsvc/http/internal/LegacyRequestAccess.java
```

- [ ] **Step 2: Edit the moved file.**

Replace `package com.retailsvc.http;` with `package com.retailsvc.http.internal;`. Rename the class from `Request` to `LegacyRequestAccess` and make it package-private (`final class LegacyRequestAccess`). Keep the `public static final ScopedValue<RequestContext> CONTEXT` (it's still needed by the filter for one more task), but its public-ness is no longer relevant.

- [ ] **Step 3: Update every consumer.**

Use a find-and-replace on `com.retailsvc.http.Request` references in `src/main/` and `src/test/`. Three categories:

- In `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`: `import com.retailsvc.http.Request;` → `// (none; LegacyRequestAccess is in this package)` and `Request.CONTEXT` → `LegacyRequestAccess.CONTEXT`.
- In `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`: same — switch to `LegacyRequestAccess.CONTEXT.get()` etc.
- In tests and `ServerLauncher`: replace all `Request.parsed()` / `Request.bytes()` / `Request.operationId()` / `Request.pathParams()` / `Request.current()` calls with the same names on `LegacyRequestAccess` (which now needs the static accessor methods reinstated since handlers in tests still use them):

```java
public static byte[] bytes() { return CONTEXT.get().body(); }
public static Object parsed() { return CONTEXT.get().parsedBody(); }
public static String operationId() { return CONTEXT.get().operationId(); }
public static Map<String, String> pathParams() { return CONTEXT.get().pathParameters(); }
public static RequestContext current() { return CONTEXT.get(); }
```

And tests / launcher import `LegacyRequestAccess` instead of `Request`.

- [ ] **Step 4: Verify all unit + IT tests still pass.**

Run: `mvn verify -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add -A
git commit -m "refactor: Move static Request accessors to internal LegacyRequestAccess"
```

---

## Task 8: Introduce new `Request`, `ResponseBuilder`, `RequestHandler`

Add the new types without changing any existing consumer. After this task, both the legacy `LegacyRequestAccess` path *and* the new types coexist; Task 9 switches consumers over and Task 10 deletes the legacy path.

**Files:**
- Create: `src/main/java/com/retailsvc/http/Request.java` (new class)
- Create: `src/main/java/com/retailsvc/http/RequestHandler.java`
- Create: `src/main/java/com/retailsvc/http/ResponseBuilder.java`
- Create: `src/main/java/com/retailsvc/http/internal/DefaultResponseBuilder.java`
- Create: `src/test/java/com/retailsvc/http/RequestResponseGatewayTest.java`

- [ ] **Step 1: Write the failing response-gateway test.**

Create `src/test/java/com/retailsvc/http/RequestResponseGatewayTest.java`:

```java
package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestResponseGatewayTest extends ServerBaseTest {

  @Test
  void respondJsonWritesBodyAndContentType() throws Exception {
    RequestHandler echo =
        req -> req.respond(200).json(Map.of("op", req.operationId()));
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getRoot", echo, "postData", echo))
            .port(0)
            .build();
    HttpClient client =
        HttpClient.newBuilder()
            .executor(newVirtualThreadPerTaskExecutor())
            .version(HTTP_1_1)
            .build();
    var resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"n\":1}"))
                .build(),
            ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(resp.body()).contains("\"op\":\"postData\"");
  }

  @Test
  void respondEmptyUses204Style() throws Exception {
    RequestHandler ok = req -> req.respond(204).empty();
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getRoot", ok, "postData", ok))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:%d/api/v1/".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(204);
    assertThat(resp.body()).isEmpty();
  }

  @Test
  void respondStreamUsesChunkedEncoding() throws Exception {
    RequestHandler streamer =
        req -> {
          try (var out = req.respond(200).contentType("text/plain").stream()) {
            out.write("hello ".getBytes());
            out.write("world".getBytes());
          }
        };
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getRoot", streamer, "postData", streamer))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:%d/api/v1/".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).isEqualTo("hello world");
  }
}
```

(Compile fails until Steps 2-5 complete and Task 9 wires `handlers(Map<String, RequestHandler>)`. We accept the temporary RED state; this test goes green after Task 9.)

Run: `mvn test -Dtest=RequestResponseGatewayTest -q`
Expected: compile error.

- [ ] **Step 2: Create `ResponseBuilder` interface.**

Create `src/main/java/com/retailsvc/http/ResponseBuilder.java`:

```java
package com.retailsvc.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Fluent response builder returned by {@link Request#respond(int)}. Each {@code Request} permits
 * exactly one terminal call ({@link #empty()}, {@link #bytes(byte[])}, {@link #text(String)},
 * {@link #json(Object)}, {@link #body(String, Object)}, {@link #stream()}, or
 * {@link #stream(long)}); calling any of them after the first throws
 * {@link IllegalStateException}. {@link #header(String, String)} / {@link #contentType(String)}
 * must be called before the terminal.
 */
public interface ResponseBuilder {

  ResponseBuilder header(String name, String value);

  ResponseBuilder contentType(String contentType);

  void empty() throws IOException;

  void bytes(byte[] body) throws IOException;

  void text(String body) throws IOException;

  void json(Object body) throws IOException;

  void body(String mediaType, Object body) throws IOException;

  OutputStream stream() throws IOException;

  OutputStream stream(long length) throws IOException;
}
```

- [ ] **Step 3: Create `RequestHandler` interface.**

Create `src/main/java/com/retailsvc/http/RequestHandler.java`:

```java
package com.retailsvc.http;

import java.io.IOException;

/**
 * Handles a single request identified by OpenAPI {@code operationId}. Registered on
 * {@link OpenApiServer.Builder#handlers(java.util.Map)} by operation ID.
 */
@FunctionalInterface
public interface RequestHandler {
  void handle(Request request) throws IOException;
}
```

- [ ] **Step 4: Create `DefaultResponseBuilder`.**

Create `src/main/java/com/retailsvc/http/internal/DefaultResponseBuilder.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.ResponseBuilder;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultResponseBuilder implements ResponseBuilder {

  private static final String CONTENT_TYPE = "Content-Type";

  private final HttpExchange exchange;
  private final int status;
  private final Map<String, TypeMapper> mappers;
  private final Map<String, String> pendingHeaders = new LinkedHashMap<>();
  private boolean terminated;

  public DefaultResponseBuilder(
      HttpExchange exchange, int status, Map<String, TypeMapper> mappers) {
    this.exchange = exchange;
    this.status = status;
    this.mappers = mappers;
  }

  @Override
  public ResponseBuilder header(String name, String value) {
    checkNotTerminated();
    pendingHeaders.put(name, value);
    return this;
  }

  @Override
  public ResponseBuilder contentType(String contentType) {
    return header(CONTENT_TYPE, contentType);
  }

  @Override
  public void empty() throws IOException {
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, -1);
  }

  @Override
  public void bytes(byte[] body) throws IOException {
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  @Override
  public void text(String body) throws IOException {
    pendingHeaders.putIfAbsent(CONTENT_TYPE, "text/plain; charset=UTF-8");
    bytes(body.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void json(Object body) throws IOException {
    this.body("application/json", body);
  }

  @Override
  public void body(String mediaType, Object value) throws IOException {
    TypeMapper mapper = mappers.get(mediaType.toLowerCase(java.util.Locale.ROOT));
    if (mapper == null) {
      throw new IllegalStateException("No TypeMapper registered for " + mediaType);
    }
    pendingHeaders.putIfAbsent(CONTENT_TYPE, mediaType);
    bytes(mapper.writeTo(value));
  }

  @Override
  public OutputStream stream() throws IOException {
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, 0);
    return exchange.getResponseBody();
  }

  @Override
  public OutputStream stream(long length) throws IOException {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative");
    }
    terminate();
    applyHeaders();
    exchange.sendResponseHeaders(status, length);
    return exchange.getResponseBody();
  }

  private void terminate() {
    checkNotTerminated();
    terminated = true;
  }

  private void checkNotTerminated() {
    if (terminated) {
      throw new IllegalStateException("Response already sent");
    }
  }

  private void applyHeaders() {
    pendingHeaders.forEach(exchange.getResponseHeaders()::add);
  }
}
```

- [ ] **Step 5: Create the new `Request` class.**

Create `src/main/java/com/retailsvc/http/Request.java`:

```java
package com.retailsvc.http;

import com.retailsvc.http.internal.DefaultResponseBuilder;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/**
 * The per-request handle passed to {@link RequestHandler}. Carries the parsed body, path
 * parameters, operation ID, and a fluent {@link ResponseBuilder} for writing the response.
 */
public final class Request {

  private final HttpExchange exchange;
  private final byte[] body;
  private final Object parsed;
  private final String operationId;
  private final Map<String, String> pathParameters;
  private final Map<String, TypeMapper> bodyMappers;

  public Request(
      HttpExchange exchange,
      byte[] body,
      Object parsed,
      String operationId,
      Map<String, String> pathParameters,
      Map<String, TypeMapper> bodyMappers) {
    this.exchange = exchange;
    this.body = body;
    this.parsed = parsed;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.bodyMappers = bodyMappers;
  }

  public byte[] bytes() {
    return body;
  }

  public Object parsed() {
    return parsed;
  }

  public String operationId() {
    return operationId;
  }

  public Map<String, String> pathParams() {
    return pathParameters;
  }

  public String header(String name) {
    return exchange.getRequestHeaders().getFirst(name);
  }

  public ResponseBuilder respond(int status) {
    return new DefaultResponseBuilder(exchange, status, bodyMappers);
  }
}
```

- [ ] **Step 6: Compile-check (no consumers yet, the new types coexist with `LegacyRequestAccess`).**

Run: `mvn test-compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/com/retailsvc/http/Request.java \
        src/main/java/com/retailsvc/http/RequestHandler.java \
        src/main/java/com/retailsvc/http/ResponseBuilder.java \
        src/main/java/com/retailsvc/http/internal/DefaultResponseBuilder.java \
        src/test/java/com/retailsvc/http/RequestResponseGatewayTest.java
git commit -m "feat: Add Request, ResponseBuilder, RequestHandler types"
```

---

## Task 9: Switch builder, filter, and dispatcher to `RequestHandler`/`Request`

The handler-API cutover: `Builder.handlers(...)` takes `Map<String, RequestHandler>`. The filter builds a `Request` and binds it to an internal `ScopedValue<Request>`. The dispatcher reads from that scope and calls `RequestHandler`.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` (Builder + constructor + extras typing)
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`
- Modify: `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`
- Modify: every test handler in `src/test/`
- Modify: `src/test/java/com/retailsvc/http/start/ServerLauncher.java` and any sibling launchers/handlers

- [ ] **Step 1: Change `DispatchHandler` to take `Map<String, RequestHandler>` and read from a new internal `ScopedValue<Request>`.**

Replace `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {

  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

  private final Map<String, RequestHandler> handlers;

  public DispatchHandler(Map<String, RequestHandler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler h = handlers.get(request.operationId());
    if (h == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    h.handle(request);
  }
}
```

- [ ] **Step 2: Update `RequestPreparationFilter` to build a `Request` and bind `DispatchHandler.CURRENT`.**

In `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`:

- Remove the `runWithRequestContext` and `IORunnable` (replaced below).
- Remove references to `LegacyRequestAccess.CONTEXT` (the legacy scope is no longer bound; we'll delete `LegacyRequestAccess` in Task 10).
- Replace the body of `doFilter` after `validateAndParseBody`:

```java
Request request =
    new Request(
        exchange,
        body,
        parsedBody,
        op.operationId(),
        match.pathParameters(),
        bodyMappers);

try {
  ScopedValue.where(DispatchHandler.CURRENT, request)
      .call(
          () -> {
            chain.doFilter(exchange);
            return null;
          });
} catch (IOException | RuntimeException e) {
  throw e;
} catch (Exception e) {
  throw new IOException(e);
}
```

Add the imports: `com.retailsvc.http.Request`, `com.retailsvc.http.TypeMapper` (already), and `com.retailsvc.http.internal.DispatchHandler` (already in same package, so no import).

- [ ] **Step 3: Update `OpenApiServer` constructor and Builder to pass `Map<String, RequestHandler>` to `DispatchHandler`.**

In `src/main/java/com/retailsvc/http/OpenApiServer.java`:

- Change the constructor parameter `Map<String, HttpHandler> handlers` to `Map<String, RequestHandler> handlers`. The `ctx.setHandler(new DispatchHandler(handlers));` call still compiles.
- Change the `Builder` field `Map<String, HttpHandler> handlers` to `Map<String, RequestHandler> handlers`. Update `Builder.handlers(...)` signature accordingly.
- The `addHandler(String, HttpHandler)` extras stay raw `HttpHandler` — they are not OpenAPI-dispatched.
- Update imports: add `import com.retailsvc.http.RequestHandler;`.

- [ ] **Step 4: Migrate all test handlers from `HttpHandler` to `RequestHandler`.**

In `src/test/java/com/retailsvc/http/ServerBaseTest.java`:

```java
protected OpenApiServer newServer(Map<String, RequestHandler> handlers) { /* unchanged otherwise */ }
```

Update the `import com.sun.net.httpserver.HttpHandler;` → `import com.retailsvc.http.RequestHandler;`.

Every test handler in `src/test/` that builds `Map<String, HttpHandler>` with lambda `ex -> { ... }` becomes `Map<String, RequestHandler>` with lambda `req -> { ... }`. The body of each handler is rewritten:

| Before (HttpHandler) | After (RequestHandler) |
| --- | --- |
| `LegacyRequestAccess.parsed()` | `req.parsed()` |
| `LegacyRequestAccess.bytes()` | `req.bytes()` |
| `LegacyRequestAccess.operationId()` | `req.operationId()` |
| `LegacyRequestAccess.pathParams()` | `req.pathParams()` |
| `ex.getResponseHeaders().add("Content-Type", "application/json")` + `sendResponseHeaders(200, bytes.length)` + `out.write(bytes)` | `req.respond(200).contentType("application/json").bytes(bytes)` *or* `req.respond(200).json(value)` |
| `ex.sendResponseHeaders(204, -1)` | `req.respond(204).empty()` |

Touch every test file under `src/test/java/com/retailsvc/http/` that constructs handlers. The compiler will list them; iterate until `mvn test-compile` is green.

`ExtraHandlersIT.java` uses raw `HttpHandler` for extras — leave that alone (`addHandler(path, HttpHandler)` is unchanged).

- [ ] **Step 5: Migrate the example launcher.**

In `src/test/java/com/retailsvc/http/start/ServerLauncher.java` and any sibling `*Handler.java` files in that package: convert handlers from `HttpHandler` to `RequestHandler`. Replace `Request.parsed()` (currently routed through `LegacyRequestAccess`) with `request.parsed()` on the handler parameter.

- [ ] **Step 6: Run the full test suite (unit + IT).**

Run: `mvn verify -q`
Expected: BUILD SUCCESS. All gateway tests (`RequestResponseGatewayTest`) now pass.

- [ ] **Step 7: Commit.**

```bash
git add -A
git commit -m "feat!: Switch handlers to RequestHandler receiving Request"
```

---

## Task 10: Delete `LegacyRequestAccess` and `RequestContext`

With no consumers left, remove the legacy scaffolding.

**Files:**
- Delete: `src/main/java/com/retailsvc/http/internal/LegacyRequestAccess.java`
- Delete: `src/main/java/com/retailsvc/http/internal/RequestContext.java`

- [ ] **Step 1: Confirm no remaining references.**

Run: `grep -rn "LegacyRequestAccess\|RequestContext" src/main src/test`
Expected: no matches. If any remain, migrate them to the new `Request` API.

- [ ] **Step 2: Delete the files.**

```bash
git rm src/main/java/com/retailsvc/http/internal/LegacyRequestAccess.java src/main/java/com/retailsvc/http/internal/RequestContext.java
```

- [ ] **Step 3: Run tests.**

Run: `mvn verify -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit.**

```bash
git commit -m "refactor: Remove LegacyRequestAccess and RequestContext"
```

---

## Task 11: README and final pass

Document the new API surface and the Gson fallback / write caveat.

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Read the current README to find the JsonMapper / handler sections.**

Run: `grep -n "JsonMapper\|jsonMapper\|HttpHandler\|Request\\.parsed" README.md`

- [ ] **Step 2: Edit each section.**

For each match:

- Replace `jsonMapper(body -> gson.fromJson(...))` examples with either:
  - the minimal "Gson is on classpath — fallback handles it" example, or
  - the explicit `bodyMapper("application/json", customMapper)` example.
- Replace `HttpHandler` handler examples with `RequestHandler` lambdas that use `request.respond(200).json(...)`.
- Add a short "JSON mapping" subsection documenting:
  - the Gson fallback (auto-registered when Gson is on the classpath, integer-preserving, JSR-310 write as ISO-8601);
  - the write caveat: for non-ISO date formats, custom naming, or custom types, register your own `TypeMapper` for `application/json`.

- [ ] **Step 3: Verify Markdown still passes the editorconfig hook.**

Run: `pre-commit run --files README.md`
Expected: all hooks pass.

- [ ] **Step 4: Run the full suite one more time.**

Run: `mvn verify -q`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit.**

```bash
git add README.md
git commit -m "docs: Update README for TypeMapper and RequestHandler"
```

---

## Final verification

- [ ] Run `mvn verify` end-to-end one final time and confirm `target/site/jacoco/` shows coverage for `TypeMapper`, `GsonJsonMapper`, `Request`, `DefaultResponseBuilder`, and `RequestHandler` paths.
- [ ] Confirm the changes against the spec sections: TypeMapper, Built-in defaults, Optional Gson fallback (incl. integer + JSR-310 adapters), Request (read API + response gateway), RequestHandler, Builder shape, Filter→dispatcher handoff, Breaking changes, Testing.
- [ ] `grep -rn "JsonMapper\|RequestContext\|LegacyRequestAccess" src/` returns nothing.
