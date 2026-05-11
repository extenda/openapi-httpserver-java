# OpenAPI Extensions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve `x-*` keys on parsed `Spec`, `Info`, `Operation`, and every concrete `Schema` record, exposed as `Map<String, Object> extensions()`.

**Architecture:** Each affected record gains an `extensions` component (final field). A small `extractExtensions` helper filters a raw `Map<String, Object>` to its `x-*` entries and is invoked at every parse site. `Schema` (sealed interface) gains an abstract `extensions()` method that all 16 concrete records implement.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-05-08-openapi-extensions-design.md`

**Conventions to honor:**
- Google Java Formatter (pre-commit autoruns; never hand-format).
- Always use curly braces — no brace-less one-liners.
- Test method names: camelCase only.
- `openapi.json` and `openapi.yaml` test fixtures must mirror each other.
- Conventional Commits (commitlint enforces).
- No `Co-Authored-By` trailer.
- LSP diagnostics after each edit; fix type errors immediately.

**Scale note:** Adding a record component requires updating every constructor call site. There are roughly 100 schema-construction sites across main + test code. Use Maven (`mvn compile`) and the Java compiler errors as a TODO list — every red squiggle becomes a `, Map.of()` addition.

---

## File Structure

**Modify (records):**
- `src/main/java/com/retailsvc/http/spec/Spec.java`
- `src/main/java/com/retailsvc/http/spec/Info.java`
- `src/main/java/com/retailsvc/http/spec/Operation.java`
- `src/main/java/com/retailsvc/http/spec/schema/Schema.java` (interface)
- All 16 concrete schemas in `src/main/java/com/retailsvc/http/spec/schema/`:
  `StringSchema`, `NumberSchema`, `IntegerSchema`, `BooleanSchema`, `NullSchema`, `ObjectSchema`, `ArraySchema`, `OneOfSchema`, `AnyOfSchema`, `AllOfSchema`, `NotSchema`, `ConstSchema`, `EnumSchema`, `RefSchema`, `AlwaysSchema`, `NeverSchema`.
- `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`

**Modify (tests):**
- Existing test files where records are constructed (all must gain a trailing `Map.of()`):
  - `src/test/java/com/retailsvc/http/spec/SpecRecordsTest.java`
  - `src/test/java/com/retailsvc/http/spec/OperationTest.java`
  - `src/test/java/com/retailsvc/http/spec/schema/AdditionalPropertiesTest.java`
  - `src/test/java/com/retailsvc/http/spec/schema/CombinatorScaffoldTest.java`
  - `src/test/java/com/retailsvc/http/spec/schema/ContainerSchemasTest.java`
  - `src/test/java/com/retailsvc/http/spec/schema/PrimitiveSchemasTest.java`
  - `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`
  - `src/test/java/com/retailsvc/http/validate/*.java` (any that construct schemas inline — e.g. `StringIntegerNumberTest`, `ArrayValidationTest`, etc.)
  - Any other test that constructs a record from this list.

**Create (new tests):**
- `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java` — covers `Spec`, `Info`, `Operation`, and schema extensions in one focused test class.

**Modify (fixtures):**
- `src/test/resources/openapi.json` (add `x-permissions` to one operation).
- `src/test/resources/openapi.yaml` (mirror).

---

## Task 1: Add `extensions` to `Spec`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java`
- Create: `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java`

- [ ] **Step 1: Verify baseline is green**

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java`:

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtensionsTest {

  @Test
  void specExtensionsExposeTopLevelXKeys() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of(),
            "x-vendor-build",
            "abc");
    Spec spec = Spec.from(raw);
    assertThat(spec.extensions()).containsEntry("x-vendor-build", "abc");
  }

  @Test
  void specExtensionsEmptyWhenNoXKeys() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of());
    Spec spec = Spec.from(raw);
    assertThat(spec.extensions()).isEmpty();
  }
}
```

- [ ] **Step 3: Run test to verify it fails (compilation error: no `extensions()` method)**

Run: `mvn test -Dtest=ExtensionsTest`
Expected: COMPILATION FAILURE — `extensions()` is undefined on `Spec`.

- [ ] **Step 4: Add `extensions` component to `Spec` and wire the parser**

In `src/main/java/com/retailsvc/http/spec/Spec.java`:

a) Add `Map<String, Object> extensions` as the last record component:

```java
public record Spec(
    String openapi,
    Info info,
    List<Server> servers,
    List<Operation> operations,
    Map<String, Schema> componentSchemas,
    Map<String, Parameter> componentParameters,
    String basePath,
    Map<String, Schema> schemaRefIndex,
    Map<String, Parameter> parameterRefIndex,
    Map<String, Object> extensions) {
```

b) Add a package-private helper inside the class (just below the `PARAMETER_REF_PREFIX` constant):

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

c) Update `Spec.from(raw)` to pass `extractExtensions(raw)` as the last argument:

```java
return new Spec(
    openapi,
    info,
    servers,
    operations,
    componentSchemas,
    componentParameters,
    computeBasePath(servers),
    indexByRef(componentSchemas, SCHEMA_REF_PREFIX),
    indexByRef(componentParameters, PARAMETER_REF_PREFIX),
    extractExtensions(raw));
```

- [ ] **Step 5: Find every other construction of `Spec` and add `Map.of()`**

Run: `mvn compile && mvn test-compile 2>&1 | tail -30`
Expected: any compilation errors point to other `new Spec(...)` call sites missing the new argument.

Fix each by appending `, Map.of()` as the last argument. (Likely there are very few — `Spec` is mostly constructed via `Spec.from`.)

- [ ] **Step 6: Run tests**

Run: `mvn test -Dtest=ExtensionsTest`
Expected: PASS.

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Spec.java src/test/java/com/retailsvc/http/spec/ExtensionsTest.java
git commit -m "feat: Preserve OpenAPI extensions on Spec"
```

---

## Task 2: Add `extensions` to `Info`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/spec/Info.java`
- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java`
- Modify: `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java`

- [ ] **Step 1: Append failing test**

In `ExtensionsTest.java`, add:

```java
@Test
void infoExtensionsExposeXKeys() {
  Map<String, Object> raw =
      Map.of(
          "openapi",
          "3.1.0",
          "info",
          Map.of("title", "t", "version", "1", "x-contact-team", "platform"),
          "servers",
          List.of(Map.of("url", "https://example.com")),
          "paths",
          Map.of());
  Spec spec = Spec.from(raw);
  assertThat(spec.info().extensions()).containsEntry("x-contact-team", "platform");
}
```

- [ ] **Step 2: Run test — expect compilation failure**

Run: `mvn test -Dtest=ExtensionsTest#infoExtensionsExposeXKeys`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Update `Info` and `parseInfo`**

In `src/main/java/com/retailsvc/http/spec/Info.java`:

```java
package com.retailsvc.http.spec;

import java.util.Map;

public record Info(String title, String version, Map<String, Object> extensions) {}
```

In `src/main/java/com/retailsvc/http/spec/Spec.java`, update `parseInfo`:

```java
private static Info parseInfo(Map<String, Object> raw) {
  return new Info((String) raw.get("title"), (String) raw.get("version"), extractExtensions(raw));
}
```

- [ ] **Step 4: Fix all other `new Info(...)` call sites**

Run: `mvn compile && mvn test-compile 2>&1 | grep -E "error|Info"`
Expected: list of `new Info(...)` call sites in tests; append `, Map.of()` to each.

Known site to update: `src/test/java/com/retailsvc/http/spec/SpecRecordsTest.java` line 36 — change `new Info("test", "1.0.0")` to `new Info("test", "1.0.0", Map.of())`.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=ExtensionsTest`
Expected: PASS.

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Info.java src/main/java/com/retailsvc/http/spec/Spec.java src/test/java/com/retailsvc/http/spec/ExtensionsTest.java src/test/java/com/retailsvc/http/spec/SpecRecordsTest.java
git commit -m "feat: Preserve OpenAPI extensions on Info"
```

---

## Task 3: Add `extensions` to `Operation`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/spec/Operation.java`
- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java`
- Modify: `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java`
- Modify: `src/test/java/com/retailsvc/http/spec/OperationTest.java`

- [ ] **Step 1: Append failing test**

In `ExtensionsTest.java`, add:

```java
@Test
void operationExtensionsExposeXPermissions() {
  Map<String, Object> raw =
      Map.of(
          "openapi",
          "3.1.0",
          "info",
          Map.of("title", "t", "version", "1"),
          "servers",
          List.of(Map.of("url", "https://example.com")),
          "paths",
          Map.of(
              "/promotions",
              Map.of(
                  "post",
                  Map.of(
                      "operationId",
                      "createPromotion",
                      "x-permissions",
                      List.of("pro.promotion.create"),
                      "responses",
                      Map.of()))));
  Spec spec = Spec.from(raw);
  Operation op = spec.operations().getFirst();
  assertThat(op.extensions()).containsEntry("x-permissions", List.of("pro.promotion.create"));
}
```

- [ ] **Step 2: Run test — expect compilation failure**

Run: `mvn test -Dtest=ExtensionsTest#operationExtensionsExposeXPermissions`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Update `Operation` record and `parseOperation`**

In `src/main/java/com/retailsvc/http/spec/Operation.java`:

```java
package com.retailsvc.http.spec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Operation(
    String operationId,
    HttpMethod method,
    PathTemplate path,
    Optional<RequestBody> requestBody,
    List<Parameter> parameters,
    Map<String, Response> responses,
    Map<String, Object> extensions) {}
```

In `src/main/java/com/retailsvc/http/spec/Spec.java`, change the last line of `parseOperation`:

```java
return new Operation(opId, method, path, body, params, responses, extractExtensions(raw));
```

- [ ] **Step 4: Fix all other `new Operation(...)` call sites**

Run: `mvn compile && mvn test-compile 2>&1 | grep -E "error|Operation"`
Expected: list of `new Operation(...)` call sites.

Known site: `src/test/java/com/retailsvc/http/spec/OperationTest.java` line 21 — append `, Map.of()`.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=ExtensionsTest`
Expected: PASS.

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Operation.java src/main/java/com/retailsvc/http/spec/Spec.java src/test/java/com/retailsvc/http/spec/ExtensionsTest.java src/test/java/com/retailsvc/http/spec/OperationTest.java
git commit -m "feat: Preserve OpenAPI extensions on Operation"
```

---

## Task 4: Add `extensions` to every `Schema` record

This is the largest mechanical task. The sealed interface gains an abstract method; each of the 16 concrete records gains a component. The compiler will surface every test/main call site that constructs a schema — fix each by appending `Map.of()` as the new last argument.

**Files (main):**
- Modify: `src/main/java/com/retailsvc/http/spec/schema/Schema.java`
- Modify: every concrete schema record in `src/main/java/com/retailsvc/http/spec/schema/`
- Modify: `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`

**Files (tests):**
- Modify: `src/test/java/com/retailsvc/http/spec/schema/AdditionalPropertiesTest.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/CombinatorScaffoldTest.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/ContainerSchemasTest.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/PrimitiveSchemasTest.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`
- Modify: any test under `src/test/java/com/retailsvc/http/validate/` that constructs a schema inline
- Modify: `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java` (add schema extension tests)

- [ ] **Step 1: Append failing schema extension tests**

In `ExtensionsTest.java`, add tests covering at least one primitive, one container, and one combinator (the rest share the parser code path):

```java
@Test
void objectSchemaExtensionsExposeXKeys() {
  Map<String, Object> raw =
      Map.of(
          "openapi",
          "3.1.0",
          "info",
          Map.of("title", "t", "version", "1"),
          "servers",
          List.of(Map.of("url", "https://example.com")),
          "paths",
          Map.of(),
          "components",
          Map.of(
              "schemas",
              Map.of(
                  "Promotion",
                  Map.of("type", "object", "properties", Map.of(), "x-ui-hint", "card"))));
  Spec spec = Spec.from(raw);
  assertThat(spec.componentSchemas().get("Promotion").extensions())
      .containsEntry("x-ui-hint", "card");
}

@Test
void stringSchemaExtensionsExposeXKeys() {
  Map<String, Object> raw =
      Map.of(
          "openapi",
          "3.1.0",
          "info",
          Map.of("title", "t", "version", "1"),
          "servers",
          List.of(Map.of("url", "https://example.com")),
          "paths",
          Map.of(),
          "components",
          Map.of(
              "schemas",
              Map.of("Code", Map.of("type", "string", "x-format-hint", "slug"))));
  Spec spec = Spec.from(raw);
  assertThat(spec.componentSchemas().get("Code").extensions())
      .containsEntry("x-format-hint", "slug");
}

@Test
void oneOfSchemaExtensionsExposeXKeys() {
  Map<String, Object> raw =
      Map.of(
          "openapi",
          "3.1.0",
          "info",
          Map.of("title", "t", "version", "1"),
          "servers",
          List.of(Map.of("url", "https://example.com")),
          "paths",
          Map.of(),
          "components",
          Map.of(
              "schemas",
              Map.of(
                  "Either",
                  Map.of(
                      "oneOf",
                      List.of(Map.of("type", "string"), Map.of("type", "integer")),
                      "x-discriminator-hint",
                      "kind"))));
  Spec spec = Spec.from(raw);
  assertThat(spec.componentSchemas().get("Either").extensions())
      .containsEntry("x-discriminator-hint", "kind");
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

Run: `mvn test -Dtest=ExtensionsTest`
Expected: COMPILATION FAILURE — schemas have no `extensions()` method.

- [ ] **Step 3: Add abstract method to `Schema` interface**

In `src/main/java/com/retailsvc/http/spec/schema/Schema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public sealed interface Schema
    permits StringSchema,
        NumberSchema,
        IntegerSchema,
        BooleanSchema,
        ObjectSchema,
        ArraySchema,
        NullSchema,
        RefSchema,
        OneOfSchema,
        AnyOfSchema,
        AllOfSchema,
        NotSchema,
        ConstSchema,
        EnumSchema,
        AlwaysSchema,
        NeverSchema {
  Set<TypeName> types();

  Map<String, Object> extensions();
}
```

- [ ] **Step 4: Add `extensions` component to every concrete schema record**

For each file in `src/main/java/com/retailsvc/http/spec/schema/`, add `Map<String, Object> extensions` as the final component. The full list:

```java
// StringSchema.java
public record StringSchema(
    Set<TypeName> types,
    String pattern,
    Integer minLength,
    Integer maxLength,
    String format,
    List<String> enumValues,
    Map<String, Object> extensions)
    implements Schema {}

// NumberSchema.java
public record NumberSchema(
    Set<TypeName> types,
    Number minimum,
    Number maximum,
    Number exclusiveMinimum,
    Number exclusiveMaximum,
    Number multipleOf,
    String format,
    Map<String, Object> extensions)
    implements Schema {}

// IntegerSchema.java
public record IntegerSchema(
    Set<TypeName> types,
    Long minimum,
    Long maximum,
    Long exclusiveMinimum,
    Long exclusiveMaximum,
    Long multipleOf,
    String format,
    Map<String, Object> extensions)
    implements Schema {}

// BooleanSchema.java
public record BooleanSchema(Set<TypeName> types, Map<String, Object> extensions)
    implements Schema {}

// NullSchema.java — preserve any existing methods (e.g., types())
public record NullSchema(Map<String, Object> extensions) implements Schema {
  // keep existing types() override if present
}

// ObjectSchema.java
public record ObjectSchema(
    Set<TypeName> types,
    Map<String, Schema> properties,
    List<String> required,
    AdditionalProperties additionalProperties,
    Integer minProperties,
    Integer maxProperties,
    Map<String, Object> extensions)
    implements Schema {}

// ArraySchema.java
public record ArraySchema(
    Set<TypeName> types,
    Schema items,
    Integer minItems,
    Integer maxItems,
    boolean uniqueItems,
    Map<String, Object> extensions)
    implements Schema {}

// RefSchema.java — preserve existing types() override
public record RefSchema(String pointer, Map<String, Object> extensions) implements Schema {
  // keep existing types() override
}

// OneOfSchema.java
public record OneOfSchema(List<Schema> options, Map<String, Object> extensions)
    implements Schema {
  // keep existing types() override
}

// AnyOfSchema.java
public record AnyOfSchema(List<Schema> options, Map<String, Object> extensions)
    implements Schema {
  // keep existing types() override
}

// AllOfSchema.java
public record AllOfSchema(List<Schema> parts, Map<String, Object> extensions)
    implements Schema {
  // keep existing types() override
}

// NotSchema.java
public record NotSchema(Schema schema, Map<String, Object> extensions) implements Schema {
  // keep existing types() override
}

// ConstSchema.java
public record ConstSchema(Object value, Map<String, Object> extensions) implements Schema {
  // keep existing types() override
}

// EnumSchema.java
public record EnumSchema(List<Object> values, Map<String, Object> extensions)
    implements Schema {
  // keep existing types() override
}

// AlwaysSchema.java
public record AlwaysSchema(Map<String, Object> extensions) implements Schema {
  // keep existing types() override
}

// NeverSchema.java
public record NeverSchema(Map<String, Object> extensions) implements Schema {
  // keep existing types() override
}
```

**Important:** Open each file before editing and preserve any existing `types()` override and any other methods inside the record body. Add only the new component to the signature; do not remove or rewrite the body.

- [ ] **Step 5: Update `SchemaParser` to thread extensions through every record constructor**

In `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`:

a) Add the helper (same shape as `Spec.extractExtensions`, package-private):

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

b) For `parse(Object raw)` boolean branch (`AlwaysSchema` / `NeverSchema`), pass `Map.of()` since booleans have no extensions:

```java
public static Schema parse(Object raw) {
  if (raw instanceof Boolean b) {
    return b ? new AlwaysSchema(Map.of()) : new NeverSchema(Map.of());
  }
  ...
}
```

c) For `parseMap(raw)`, every concrete construction must pass `extractExtensions(raw)` (or `Map.of()` for synthetic schemas where there is no source raw map — e.g. `permissiveObject()`, `new NullSchema(...)` synthesized for missing array items, `new ConstSchema(...)`).

The synthesized cases (no source raw) should pass `Map.of()`:

- `permissiveObject()` — no source raw.
- The `items == null` branch in `parseArray`, building `new NullSchema(...)` — no source raw.
- `parseAdditionalProperties`'s boolean cases — no source raw.

The "has source raw" cases pass `extractExtensions(raw)`:

- `new RefSchema((String) raw.get("$ref"), extractExtensions(raw))`
- `new ConstSchema(raw.get("const"), extractExtensions(raw))`
- `new EnumSchema(List.copyOf((List<Object>) raw.get("enum")), extractExtensions(raw))`
- `new StringSchema(..., extractExtensions(raw))` in `parseString`
- `new IntegerSchema(..., extractExtensions(raw))` in `parseInteger`
- `new NumberSchema(..., extractExtensions(raw))` in `parseNumber`
- `new BooleanSchema(types, extractExtensions(raw))`
- `new NullSchema(extractExtensions(raw))`
- `new ObjectSchema(..., extractExtensions(raw))` in `parseObject`
- `new ArraySchema(..., extractExtensions(raw))` in `parseArray`
- The combinator branches (`new AnyOfSchema(parseList(raw, "anyOf"), extractExtensions(raw))`, similarly `OneOfSchema` and `AllOfSchema`).
- `new NotSchema(parse(raw.get("not")), extractExtensions(raw))`.

Note: `parseList(raw, "allOf")` returns a `List<Schema>` that is added directly to the assertions list (no wrapping `AllOfSchema`). When `parseMap` ends up wrapping multiple assertions in an `AllOfSchema` via the `default` arm of the final `switch`, pass `Map.of()` for that synthesized wrapper — its extensions are already on the inner schemas:

```java
default -> new AllOfSchema(List.copyOf(assertions), Map.of());
```

- [ ] **Step 6: Build to surface every other construction site**

Run: `mvn compile && mvn test-compile 2>&1 | grep -E "error" | head -50`

Each compilation error pointing at a `new XxxSchema(...)` call in a test file (or anywhere else) needs a `, Map.of()` appended as the new last argument.

Common sites in tests:
- `src/test/java/com/retailsvc/http/spec/schema/AdditionalPropertiesTest.java`
- `src/test/java/com/retailsvc/http/spec/schema/CombinatorScaffoldTest.java`
- `src/test/java/com/retailsvc/http/spec/schema/ContainerSchemasTest.java`
- `src/test/java/com/retailsvc/http/spec/schema/PrimitiveSchemasTest.java`
- `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`
- `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`
- `src/test/java/com/retailsvc/http/validate/ArrayValidationTest.java`
- `src/test/java/com/retailsvc/http/validate/ObjectValidationTest.java`
- `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java`

Iterate until `mvn test-compile` reports BUILD SUCCESS. Some tests will require touching dozens of constructor lines. Use the IDE's quick-fix or a careful manual sweep.

- [ ] **Step 7: Run the full unit test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass (including the three new `ExtensionsTest` schema cases).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema src/test/java/com/retailsvc/http/spec/ExtensionsTest.java src/test/java/com/retailsvc/http/spec/schema src/test/java/com/retailsvc/http/validate
git commit -m "feat: Preserve OpenAPI extensions on every Schema record"
```

---

## Task 5: End-to-end fixture verification

Adds `x-permissions` to a fixture operation and asserts that the value flows through `Spec.from(raw)` from the actual test fixture (not a synthetic map).

**Files:**
- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/resources/openapi.yaml`
- Modify: `src/test/java/com/retailsvc/http/spec/ExtensionsTest.java`

- [ ] **Step 1: Add `x-permissions` to one operation in `openapi.json`**

Pick an existing operation in `src/test/resources/openapi.json` (e.g., `post-data` or any small POST). Add an `"x-permissions": ["pro.promotion.create"]` key alongside `operationId`, indentation matching the surrounding keys.

- [ ] **Step 2: Mirror in `openapi.yaml`**

Add `x-permissions:` with the same value on the same operation. Match YAML indentation precisely.

- [ ] **Step 3: Append fixture round-trip test**

In `ExtensionsTest.java`, identify the path of the production code that loads `src/test/resources/openapi.json` in existing tests (search for `openapi.json` in `src/test/java/com/retailsvc/http/`). Mirror that fixture-load pattern and add:

```java
@Test
void fixtureOperationExtensionsAreReadable() {
  Spec spec = loadFixtureSpec(); // helper that mirrors existing fixture-loading pattern
  Operation op =
      spec.operations().stream()
          .filter(o -> "<operationId-with-x-permissions>".equals(o.operationId()))
          .findFirst()
          .orElseThrow();
  assertThat(op.extensions()).containsEntry("x-permissions", List.of("pro.promotion.create"));
}
```

Replace `<operationId-with-x-permissions>` with the actual operationId chosen in Step 1. Inline the existing fixture-load logic into a helper or reuse whatever helper an existing test in `src/test/java/com/retailsvc/http/` provides — search for `Spec.from` invocations against the test resource for a template.

- [ ] **Step 4: Run the test**

Run: `mvn test -Dtest=ExtensionsTest#fixtureOperationExtensionsAreReadable`
Expected: PASS.

Run: `mvn verify`
Expected: BUILD SUCCESS — full unit + IT suite green, fixtures still parse correctly.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/openapi.json src/test/resources/openapi.yaml src/test/java/com/retailsvc/http/spec/ExtensionsTest.java
git commit -m "test: Verify x-permissions flows through fixture parse"
```

---

## Task 6: Final verification

- [ ] **Step 1: Confirm full build is clean**

Run: `mvn verify`
Expected: BUILD SUCCESS, all unit + IT tests pass.

- [ ] **Step 2: Sanity-check accessor exposure**

Open the following files and confirm each declares a `Map<String, Object> extensions` component:

- `src/main/java/com/retailsvc/http/spec/Spec.java`
- `src/main/java/com/retailsvc/http/spec/Info.java`
- `src/main/java/com/retailsvc/http/spec/Operation.java`
- All 16 files under `src/main/java/com/retailsvc/http/spec/schema/` matching `*Schema.java`.

Open `src/main/java/com/retailsvc/http/spec/schema/Schema.java` and confirm it declares `Map<String, Object> extensions();` as an abstract method.

- [ ] **Step 3: Push the branch**

Per repo memory: gh CLI cannot create PRs in this repo — push and let the user open the PR manually.

```bash
git push -u origin HEAD
```

Notify the user the branch is pushed and ready for them to open the PR.
