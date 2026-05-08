# OpenAPI Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the library along the design in `docs/superpowers/specs/2026-05-07-openapi-refactor-design.md` so OpenAPI 3.1 keyword gaps become mechanical to fill, and ship Java 25 build + the typed-record-derived "free" 3.1 keywords (`minLength`/`maxLength`/`minItems`/`maxItems`/`uniqueItems`/`multipleOf`/`exclusiveMin/Max`/`type:["string","null"]`).

**Architecture:** New packages (`com.retailsvc.http.spec`, `spec.schema`, `validate`, `internal`) are built alongside the old `com.retailsvc.http.openapi.*` tree, the public `OpenApiServer` cuts over to the new types in one task, and only then do old packages get deleted. Sealed `Schema` interface + per-kind records + pattern-match dispatch in a single `DefaultValidator`. Spec parsing accepts a consumer-supplied `Map<String,Object>` (no `JsonMapper`-for-spec, no YAML callback). RFC 7807 problem+json error responses.

**Tech Stack:** Java 25, Maven (Surefire 3.5.4 / Failsafe 3.5.4 / Jacoco 0.8.14 / sortpom), JUnit 5 (Jupiter, BOM 6.0.2), AssertJ 3.27.7, Mockito 5.21.0, JDK `com.sun.net.httpserver.HttpServer`, SLF4J 2.0.17 (`provided`). Zero new runtime dependencies.

## Reference shapes

All concrete record/interface shapes live in the design doc at `docs/superpowers/specs/2026-05-07-openapi-refactor-design.md` sections "Schema model", "Spec model", "Validation", "Default error rendering", "Server wiring & body capture", "Public API surface". Each task below references the relevant section by name.

## Branch + commit hygiene

- Branch is `refactor/openapi-3.1-readiness`, already created off latest master.
- One commit per task, message in Conventional Commits form (commitlint enforces).
- After every implementation step, run `mvn -q test` before committing. Pre-commit hooks run Google Java Formatter + editorconfig-checker + commitlint.
- Never use `--no-verify`. If a hook fails, fix the underlying issue.

---

## Phase A — Build prep

### Task A1: Bump Java to 25

**Files:**
- Modify: `.java-version`
- Modify: `pom.xml` (line ~197: `<release>21</release>`)
- Modify: `Dockerfile` (line 1: base image)

- [ ] **Step 1: Update `.java-version`**

```
25
```

- [ ] **Step 2: Update `pom.xml` compiler release**

In `pom.xml` find the `maven-compiler-plugin` block:

```xml
<configuration>
  <release>25</release>
</configuration>
```

- [ ] **Step 3: Update Dockerfile base image**

```dockerfile
FROM eclipse-temurin:25-jre-alpine
```

- [ ] **Step 4: Verify build works**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all 122 tests pass.

If `mvn` picks an older JDK, ensure JAVA_HOME points at a 25 install or use `mvn -Dmaven.compiler.release=25 ...` once to confirm.

- [ ] **Step 5: Commit**

```bash
git add .java-version pom.xml Dockerfile
git commit -m "build: bump Java to 25"
```

---

## Phase B — Schema model (sealed types, no parser yet)

These tasks build the new `com.retailsvc.http.spec.schema` package alongside the old `com.retailsvc.http.openapi.model.Schema`. Old code keeps working until Phase J.

### Task B1: TypeName enum

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/TypeName.java`
- Test: `src/test/java/com/retailsvc/http/spec/schema/TypeNameTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TypeNameTest {
  @Test
  void parsesAllSevenJsonSchemaTypes() {
    assertThat(TypeName.fromJsonSchema("string")).isEqualTo(TypeName.STRING);
    assertThat(TypeName.fromJsonSchema("number")).isEqualTo(TypeName.NUMBER);
    assertThat(TypeName.fromJsonSchema("integer")).isEqualTo(TypeName.INTEGER);
    assertThat(TypeName.fromJsonSchema("boolean")).isEqualTo(TypeName.BOOLEAN);
    assertThat(TypeName.fromJsonSchema("object")).isEqualTo(TypeName.OBJECT);
    assertThat(TypeName.fromJsonSchema("array")).isEqualTo(TypeName.ARRAY);
    assertThat(TypeName.fromJsonSchema("null")).isEqualTo(TypeName.NULL);
  }

  @Test
  void unknownTypeNameThrows() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> TypeName.fromJsonSchema("widget"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TypeNameTest`
Expected: compilation failure, `TypeName` does not exist.

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.spec.schema;

public enum TypeName {
  STRING, NUMBER, INTEGER, BOOLEAN, OBJECT, ARRAY, NULL;

  public static TypeName fromJsonSchema(String name) {
    return switch (name) {
      case "string"  -> STRING;
      case "number"  -> NUMBER;
      case "integer" -> INTEGER;
      case "boolean" -> BOOLEAN;
      case "object"  -> OBJECT;
      case "array"   -> ARRAY;
      case "null"    -> NULL;
      default -> throw new IllegalArgumentException("unknown JSON Schema type: " + name);
    };
  }
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=TypeNameTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/TypeName.java \
        src/test/java/com/retailsvc/http/spec/schema/TypeNameTest.java
git commit -m "feat(schema): add TypeName enum"
```

---

### Task B2: AdditionalProperties sealed wrapper

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/AdditionalProperties.java`
- Test: `src/test/java/com/retailsvc/http/spec/schema/AdditionalPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AdditionalPropertiesTest {
  @Test
  void allowedIsDefault() {
    AdditionalProperties ap = new AdditionalProperties.Allowed();
    assertThat(ap).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void forbiddenSentinel() {
    assertThat(new AdditionalProperties.Forbidden())
        .isInstanceOf(AdditionalProperties.Forbidden.class);
  }

  @Test
  void schemaConstraintCarriesSchema() {
    Schema inner = new BooleanSchema(java.util.Set.of(TypeName.BOOLEAN));
    AdditionalProperties ap = new AdditionalProperties.SchemaConstraint(inner);
    assertThat(((AdditionalProperties.SchemaConstraint) ap).schema()).isSameAs(inner);
  }
}
```

- [ ] **Step 2: Run — fails, no Schema or BooleanSchema yet**

Run: `mvn -q test -Dtest=AdditionalPropertiesTest`
Expected: compilation failure.

- [ ] **Step 3: Stub `Schema` and `BooleanSchema` (full hierarchy comes in B3)**

Create `src/main/java/com/retailsvc/http/spec/schema/Schema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public sealed interface Schema permits BooleanSchema {
  Set<TypeName> types();
}
```

Create `src/main/java/com/retailsvc/http/spec/schema/BooleanSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record BooleanSchema(Set<TypeName> types) implements Schema {}
```

Create `src/main/java/com/retailsvc/http/spec/schema/AdditionalProperties.java`:

```java
package com.retailsvc.http.spec.schema;

public sealed interface AdditionalProperties {
  record Allowed() implements AdditionalProperties {}
  record Forbidden() implements AdditionalProperties {}
  record SchemaConstraint(Schema schema) implements AdditionalProperties {}
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=AdditionalPropertiesTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/ \
        src/test/java/com/retailsvc/http/spec/schema/AdditionalPropertiesTest.java
git commit -m "feat(schema): add Schema sealed interface, BooleanSchema, AdditionalProperties wrapper"
```

---

### Task B3: Primitive Schema records (string, number, integer, null, ref)

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/StringSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/NumberSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/IntegerSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/NullSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/RefSchema.java`
- Modify: `src/main/java/com/retailsvc/http/spec/schema/Schema.java` (extend `permits`)
- Test: `src/test/java/com/retailsvc/http/spec/schema/PrimitiveSchemasTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrimitiveSchemasTest {
  @Test
  void stringSchemaCarriesAllStringFields() {
    StringSchema s = new StringSchema(
        Set.of(TypeName.STRING), "^x.*$", 1, 64, "uuid", List.of("a", "b"));
    assertThat(s.pattern()).isEqualTo("^x.*$");
    assertThat(s.minLength()).isEqualTo(1);
    assertThat(s.maxLength()).isEqualTo(64);
    assertThat(s.format()).isEqualTo("uuid");
    assertThat(s.enumValues()).containsExactly("a", "b");
  }

  @Test
  void numberSchemaCarriesAllNumericConstraints() {
    NumberSchema n = new NumberSchema(
        Set.of(TypeName.NUMBER), 0, 100, null, 100, 5, "double");
    assertThat(n.minimum()).isEqualTo(0);
    assertThat(n.maximum()).isEqualTo(100);
    assertThat(n.exclusiveMaximum()).isEqualTo(100);
    assertThat(n.multipleOf()).isEqualTo(5);
  }

  @Test
  void integerSchemaUsesLongConstraints() {
    IntegerSchema i = new IntegerSchema(
        Set.of(TypeName.INTEGER), 1L, 2_000_000_000L, null, null, null, "int64");
    assertThat(i.maximum()).isEqualTo(2_000_000_000L);
    assertThat(i.format()).isEqualTo("int64");
  }

  @Test
  void nullSchemaTypesIsAlwaysNull() {
    assertThat(new NullSchema().types()).containsExactly(TypeName.NULL);
  }

  @Test
  void refSchemaTypesIsEmpty() {
    RefSchema r = new RefSchema("#/components/schemas/User");
    assertThat(r.pointer()).isEqualTo("#/components/schemas/User");
    assertThat(r.types()).isEmpty();
  }
}
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=PrimitiveSchemasTest`
Expected: compilation failure.

- [ ] **Step 3: Implement records**

`StringSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record StringSchema(
    Set<TypeName> types,
    String pattern,
    Integer minLength,
    Integer maxLength,
    String format,
    List<String> enumValues) implements Schema {}
```

`NumberSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NumberSchema(
    Set<TypeName> types,
    Number minimum,
    Number maximum,
    Number exclusiveMinimum,
    Number exclusiveMaximum,
    Number multipleOf,
    String format) implements Schema {}
```

`IntegerSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record IntegerSchema(
    Set<TypeName> types,
    Long minimum,
    Long maximum,
    Long exclusiveMinimum,
    Long exclusiveMaximum,
    Long multipleOf,
    String format) implements Schema {}
```

`NullSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NullSchema() implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(TypeName.NULL); }
}
```

`RefSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record RefSchema(String pointer) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

Update `Schema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public sealed interface Schema
    permits StringSchema, NumberSchema, IntegerSchema, BooleanSchema,
            NullSchema, RefSchema {
  Set<TypeName> types();
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=PrimitiveSchemasTest`
Expected: PASS (5 tests).
Then: `mvn -q test` (full suite still green — old code unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/ \
        src/test/java/com/retailsvc/http/spec/schema/PrimitiveSchemasTest.java
git commit -m "feat(schema): add primitive Schema records"
```

---

### Task B4: ObjectSchema and ArraySchema

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/ObjectSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/ArraySchema.java`
- Modify: `src/main/java/com/retailsvc/http/spec/schema/Schema.java` (extend `permits`)
- Test: `src/test/java/com/retailsvc/http/spec/schema/ContainerSchemasTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainerSchemasTest {
  @Test
  void objectSchemaCarriesPropertiesAndRequired() {
    Schema name = new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null);
    ObjectSchema o = new ObjectSchema(
        Set.of(TypeName.OBJECT),
        Map.of("name", name),
        List.of("name"),
        new AdditionalProperties.Allowed(),
        null, null);
    assertThat(o.properties()).containsKey("name");
    assertThat(o.required()).containsExactly("name");
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void arraySchemaCarriesItemsAndConstraints() {
    Schema items = new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32");
    ArraySchema a = new ArraySchema(Set.of(TypeName.ARRAY), items, 1, 10, true);
    assertThat(a.items()).isSameAs(items);
    assertThat(a.minItems()).isEqualTo(1);
    assertThat(a.maxItems()).isEqualTo(10);
    assertThat(a.uniqueItems()).isTrue();
  }
}
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=ContainerSchemasTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`ObjectSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ObjectSchema(
    Set<TypeName> types,
    Map<String, Schema> properties,
    List<String> required,
    AdditionalProperties additionalProperties,
    Integer minProperties,
    Integer maxProperties) implements Schema {}
```

`ArraySchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record ArraySchema(
    Set<TypeName> types,
    Schema items,
    Integer minItems,
    Integer maxItems,
    boolean uniqueItems) implements Schema {}
```

Update `Schema.java`:

```java
public sealed interface Schema
    permits StringSchema, NumberSchema, IntegerSchema, BooleanSchema,
            ObjectSchema, ArraySchema, NullSchema, RefSchema {
  Set<TypeName> types();
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=ContainerSchemasTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/ \
        src/test/java/com/retailsvc/http/spec/schema/ContainerSchemasTest.java
git commit -m "feat(schema): add ObjectSchema and ArraySchema records"
```

---

### Task B5: Combinator scaffold records (no validator support yet)

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/OneOfSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/AnyOfSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/AllOfSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/NotSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/ConstSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/EnumSchema.java`
- Modify: `src/main/java/com/retailsvc/http/spec/schema/Schema.java`
- Test: `src/test/java/com/retailsvc/http/spec/schema/CombinatorScaffoldTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CombinatorScaffoldTest {
  private final Schema s = new BooleanSchema(Set.of(TypeName.BOOLEAN));

  @Test void oneOfHoldsOptions()  { assertThat(new OneOfSchema(List.of(s)).options()).hasSize(1); }
  @Test void anyOfHoldsOptions()  { assertThat(new AnyOfSchema(List.of(s)).options()).hasSize(1); }
  @Test void allOfHoldsParts()    { assertThat(new AllOfSchema(List.of(s)).parts()).hasSize(1); }
  @Test void notHoldsSchema()     { assertThat(new NotSchema(s).schema()).isSameAs(s); }
  @Test void constHoldsValue()    { assertThat(new ConstSchema("x").value()).isEqualTo("x"); }
  @Test void enumHoldsValues()    { assertThat(new EnumSchema(List.of(1, 2)).values()).hasSize(2); }
  @Test void allCombinatorsTypesEmpty() {
    assertThat(new OneOfSchema(List.of(s)).types()).isEmpty();
    assertThat(new ConstSchema("x").types()).isEmpty();
  }
}
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=CombinatorScaffoldTest`
Expected: compilation failure.

- [ ] **Step 3: Implement records**

`OneOfSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record OneOfSchema(List<Schema> options) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

`AnyOfSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record AnyOfSchema(List<Schema> options) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

`AllOfSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record AllOfSchema(List<Schema> parts) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

`NotSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NotSchema(Schema schema) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

`ConstSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record ConstSchema(Object value) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

`EnumSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record EnumSchema(List<Object> values) implements Schema {
  @Override
  public Set<TypeName> types() { return Set.of(); }
}
```

Update `Schema.java`:

```java
public sealed interface Schema
    permits StringSchema, NumberSchema, IntegerSchema, BooleanSchema,
            ObjectSchema, ArraySchema, NullSchema, RefSchema,
            OneOfSchema, AnyOfSchema, AllOfSchema, NotSchema,
            ConstSchema, EnumSchema {
  Set<TypeName> types();
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=CombinatorScaffoldTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/ \
        src/test/java/com/retailsvc/http/spec/schema/CombinatorScaffoldTest.java
git commit -m "feat(schema): scaffold combinator records (oneOf/anyOf/allOf/not/const/enum)"
```

---

## Phase C — Schema parser

### Task C1: SchemaParser primitive dispatch

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`
- Test: `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaParserTest {
  @Test
  void parsesString() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "minLength", 1, "maxLength", 64));
    assertThat(s).isInstanceOf(StringSchema.class);
    StringSchema str = (StringSchema) s;
    assertThat(str.minLength()).isEqualTo(1);
    assertThat(str.maxLength()).isEqualTo(64);
  }

  @Test
  void parsesIntegerWithFormat() {
    Schema s = SchemaParser.parse(Map.of("type", "integer", "format", "int64", "minimum", 0));
    assertThat(s).isInstanceOf(IntegerSchema.class);
    assertThat(((IntegerSchema) s).format()).isEqualTo("int64");
    assertThat(((IntegerSchema) s).minimum()).isEqualTo(0L);
  }

  @Test
  void parsesNumber() {
    Schema s = SchemaParser.parse(Map.of("type", "number", "multipleOf", 0.5));
    assertThat(s).isInstanceOf(NumberSchema.class);
    assertThat(((NumberSchema) s).multipleOf()).isEqualTo(0.5);
  }

  @Test
  void parsesBoolean() {
    assertThat(SchemaParser.parse(Map.of("type", "boolean"))).isInstanceOf(BooleanSchema.class);
  }

  @Test
  void parsesNull() {
    assertThat(SchemaParser.parse(Map.of("type", "null"))).isInstanceOf(NullSchema.class);
  }

  @Test
  void parsesRef() {
    Schema s = SchemaParser.parse(Map.of("$ref", "#/components/schemas/User"));
    assertThat(s).isInstanceOf(RefSchema.class);
    assertThat(((RefSchema) s).pointer()).isEqualTo("#/components/schemas/User");
  }

  @Test
  void parsesTypeArrayWithNullForNullable() {
    Schema s = SchemaParser.parse(Map.of("type", List.of("string", "null")));
    assertThat(s).isInstanceOf(StringSchema.class);
    assertThat(s.types()).containsExactlyInAnyOrder(TypeName.STRING, TypeName.NULL);
  }

  @Test
  void parsesLegacyNullableTrueAsTypeUnion() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "nullable", true));
    assertThat(s.types()).containsExactlyInAnyOrder(TypeName.STRING, TypeName.NULL);
  }
}
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: compilation failure.

- [ ] **Step 3: Implement primitive parser**

```java
package com.retailsvc.http.spec.schema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaParser {
  private SchemaParser() {}

  @SuppressWarnings("unchecked")
  public static Schema parse(Map<String, Object> raw) {
    if (raw.containsKey("$ref")) {
      return new RefSchema((String) raw.get("$ref"));
    }

    Set<TypeName> types = parseTypes(raw);

    // Pick primary (non-null) type for record dispatch.
    TypeName primary = types.stream()
        .filter(t -> t != TypeName.NULL)
        .findFirst()
        .orElse(TypeName.NULL);

    return switch (primary) {
      case STRING  -> parseString(raw, types);
      case INTEGER -> parseInteger(raw, types);
      case NUMBER  -> parseNumber(raw, types);
      case BOOLEAN -> new BooleanSchema(types);
      case NULL    -> new NullSchema();
      case OBJECT, ARRAY ->
          throw new UnsupportedOperationException("object/array parsing comes in C2");
    };
  }

  private static Set<TypeName> parseTypes(Map<String, Object> raw) {
    Object t = raw.get("type");
    EnumSet<TypeName> out = EnumSet.noneOf(TypeName.class);
    if (t instanceof String s) {
      out.add(TypeName.fromJsonSchema(s));
    } else if (t instanceof List<?> list) {
      for (Object name : list) {
        out.add(TypeName.fromJsonSchema((String) name));
      }
    }
    if (Boolean.TRUE.equals(raw.get("nullable"))) {
      out.add(TypeName.NULL);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static StringSchema parseString(Map<String, Object> raw, Set<TypeName> types) {
    return new StringSchema(
        types,
        (String) raw.get("pattern"),
        toIntOrNull(raw.get("minLength")),
        toIntOrNull(raw.get("maxLength")),
        (String) raw.get("format"),
        (List<String>) raw.get("enum"));
  }

  private static IntegerSchema parseInteger(Map<String, Object> raw, Set<TypeName> types) {
    return new IntegerSchema(
        types,
        toLongOrNull(raw.get("minimum")),
        toLongOrNull(raw.get("maximum")),
        toLongOrNull(raw.get("exclusiveMinimum")),
        toLongOrNull(raw.get("exclusiveMaximum")),
        toLongOrNull(raw.get("multipleOf")),
        (String) raw.get("format"));
  }

  private static NumberSchema parseNumber(Map<String, Object> raw, Set<TypeName> types) {
    return new NumberSchema(
        types,
        (Number) raw.get("minimum"),
        (Number) raw.get("maximum"),
        (Number) raw.get("exclusiveMinimum"),
        (Number) raw.get("exclusiveMaximum"),
        (Number) raw.get("multipleOf"),
        (String) raw.get("format"));
  }

  private static Integer toIntOrNull(Object v) { return v == null ? null : ((Number) v).intValue(); }
  private static Long toLongOrNull(Object v)   { return v == null ? null : ((Number) v).longValue(); }
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: 8 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java \
        src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java
git commit -m "feat(schema): SchemaParser handles primitives, refs, nullable forms"
```

---

### Task C2: SchemaParser object + array dispatch

**Files:**
- Modify: `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`

- [ ] **Step 1: Add tests**

Append to `SchemaParserTest.java`:

```java
  @Test
  void parsesObjectWithRequiredAndProperties() {
    Map<String, Object> raw = Map.of(
        "type", "object",
        "required", List.of("name"),
        "properties", Map.of("name", Map.of("type", "string")));
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.required()).containsExactly("name");
    assertThat(o.properties()).containsKey("name");
    assertThat(o.properties().get("name")).isInstanceOf(StringSchema.class);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void parsesObjectWithAdditionalPropertiesFalse() {
    Map<String, Object> raw = Map.of("type", "object", "additionalProperties", false);
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Forbidden.class);
  }

  @Test
  void parsesObjectWithAdditionalPropertiesSchema() {
    Map<String, Object> raw = Map.of(
        "type", "object",
        "additionalProperties", Map.of("type", "string"));
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.SchemaConstraint.class);
  }

  @Test
  void parsesArrayWithItems() {
    Map<String, Object> raw = Map.of(
        "type", "array",
        "items", Map.of("type", "integer"),
        "minItems", 1,
        "uniqueItems", true);
    ArraySchema a = (ArraySchema) SchemaParser.parse(raw);
    assertThat(a.items()).isInstanceOf(IntegerSchema.class);
    assertThat(a.minItems()).isEqualTo(1);
    assertThat(a.uniqueItems()).isTrue();
  }
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: 4 new tests fail with `UnsupportedOperationException`.

- [ ] **Step 3: Implement**

In `SchemaParser`, replace the `OBJECT, ARRAY -> throw ...` branch:

```java
      case OBJECT  -> parseObject(raw, types);
      case ARRAY   -> parseArray(raw, types);
```

Add private methods:

```java
  @SuppressWarnings("unchecked")
  private static ObjectSchema parseObject(Map<String, Object> raw, Set<TypeName> types) {
    Map<String, Object> rawProps = (Map<String, Object>) raw.getOrDefault("properties", Map.of());
    Map<String, Schema> properties = new java.util.LinkedHashMap<>();
    for (var e : rawProps.entrySet()) {
      properties.put(e.getKey(), parse((Map<String, Object>) e.getValue()));
    }
    List<String> required = (List<String>) raw.getOrDefault("required", List.of());
    AdditionalProperties ap = parseAdditionalProperties(raw.get("additionalProperties"));
    return new ObjectSchema(
        types,
        java.util.Map.copyOf(properties),
        java.util.List.copyOf(required),
        ap,
        toIntOrNull(raw.get("minProperties")),
        toIntOrNull(raw.get("maxProperties")));
  }

  @SuppressWarnings("unchecked")
  private static AdditionalProperties parseAdditionalProperties(Object value) {
    if (value == null || Boolean.TRUE.equals(value)) {
      return new AdditionalProperties.Allowed();
    }
    if (Boolean.FALSE.equals(value)) {
      return new AdditionalProperties.Forbidden();
    }
    return new AdditionalProperties.SchemaConstraint(parse((Map<String, Object>) value));
  }

  @SuppressWarnings("unchecked")
  private static ArraySchema parseArray(Map<String, Object> raw, Set<TypeName> types) {
    Map<String, Object> items = (Map<String, Object>) raw.getOrDefault("items", Map.of());
    Schema itemSchema = items.isEmpty() ? new NullSchema() : parse(items);
    return new ArraySchema(
        types,
        itemSchema,
        toIntOrNull(raw.get("minItems")),
        toIntOrNull(raw.get("maxItems")),
        Boolean.TRUE.equals(raw.get("uniqueItems")));
  }
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: 12 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java \
        src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java
git commit -m "feat(schema): SchemaParser handles objects (with additionalProperties) and arrays"
```

---

### Task C3: SchemaParser combinator + const + enum dispatch

**Files:**
- Modify: `SchemaParser.java`, `SchemaParserTest.java`

- [ ] **Step 1: Add tests**

```java
  @Test
  void parsesOneOf() {
    Map<String, Object> raw = Map.of("oneOf", List.of(
        Map.of("type", "string"), Map.of("type", "integer")));
    OneOfSchema o = (OneOfSchema) SchemaParser.parse(raw);
    assertThat(o.options()).hasSize(2);
    assertThat(o.options().get(0)).isInstanceOf(StringSchema.class);
  }

  @Test
  void parsesAnyOfAllOfNot() {
    assertThat(SchemaParser.parse(Map.of("anyOf", List.of(Map.of("type", "string"))))).isInstanceOf(AnyOfSchema.class);
    assertThat(SchemaParser.parse(Map.of("allOf", List.of(Map.of("type", "string"))))).isInstanceOf(AllOfSchema.class);
    assertThat(SchemaParser.parse(Map.of("not", Map.of("type", "null")))).isInstanceOf(NotSchema.class);
  }

  @Test
  void parsesConst() {
    assertThat(SchemaParser.parse(Map.of("const", 42))).isInstanceOf(ConstSchema.class);
    assertThat(((ConstSchema) SchemaParser.parse(Map.of("const", "a"))).value()).isEqualTo("a");
  }

  @Test
  void parsesTopLevelEnumWithoutType() {
    Schema s = SchemaParser.parse(Map.of("enum", List.of(1, 2, 3)));
    assertThat(s).isInstanceOf(EnumSchema.class);
    assertThat(((EnumSchema) s).values()).containsExactly(1, 2, 3);
  }

  @Test
  void enumOnStringStaysAsStringSchema() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "enum", List.of("a", "b")));
    assertThat(s).isInstanceOf(StringSchema.class);
    assertThat(((StringSchema) s).enumValues()).containsExactly("a", "b");
  }
```

- [ ] **Step 2: Run — fails**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: 5 new tests fail.

- [ ] **Step 3: Add dispatch at top of `SchemaParser.parse`**

Insert these checks just after the `$ref` check, in this order:

```java
    if (raw.containsKey("oneOf")) return new OneOfSchema(parseList(raw, "oneOf"));
    if (raw.containsKey("anyOf")) return new AnyOfSchema(parseList(raw, "anyOf"));
    if (raw.containsKey("allOf")) return new AllOfSchema(parseList(raw, "allOf"));
    if (raw.containsKey("not"))   return new NotSchema(parse((Map<String, Object>) raw.get("not")));
    if (raw.containsKey("const")) return new ConstSchema(raw.get("const"));
    if (raw.containsKey("enum") && !raw.containsKey("type")) {
      return new EnumSchema(java.util.List.copyOf((List<Object>) raw.get("enum")));
    }
```

Helper:

```java
  @SuppressWarnings("unchecked")
  private static List<Schema> parseList(Map<String, Object> raw, String key) {
    List<Map<String, Object>> raws = (List<Map<String, Object>>) raw.get(key);
    List<Schema> out = new ArrayList<>(raws.size());
    for (Map<String, Object> r : raws) out.add(parse(r));
    return java.util.List.copyOf(out);
  }
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=SchemaParserTest`
Expected: all 17 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java \
        src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java
git commit -m "feat(schema): SchemaParser handles combinators, const, top-level enum"
```

---

## Phase D — Spec model

### Task D1: HttpMethod enum

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/HttpMethod.java`
- Test: `src/test/java/com/retailsvc/http/spec/HttpMethodTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HttpMethodTest {
  @Test void parsesUppercase()   { assertThat(HttpMethod.parse("GET")).isEqualTo(HttpMethod.GET); }
  @Test void parsesLowercase()   { assertThat(HttpMethod.parse("get")).isEqualTo(HttpMethod.GET); }
  @Test void parsesMixed()       { assertThat(HttpMethod.parse("PaTcH")).isEqualTo(HttpMethod.PATCH); }
  @Test void unknownThrows() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> HttpMethod.parse("foo"));
  }
}
```

- [ ] **Step 2: Run — fails (compile)**
Run: `mvn -q test -Dtest=HttpMethodTest`

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.spec;

import java.util.Locale;

public enum HttpMethod {
  GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, CONNECT;

  public static HttpMethod parse(String s) {
    return HttpMethod.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
```

- [ ] **Step 4: Verify**
Run: `mvn -q test -Dtest=HttpMethodTest` — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/HttpMethod.java \
        src/test/java/com/retailsvc/http/spec/HttpMethodTest.java
git commit -m "feat(spec): add HttpMethod enum"
```

---

### Task D2: PathTemplate

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/PathTemplate.java`
- Test: `src/test/java/com/retailsvc/http/spec/PathTemplateTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PathTemplateTest {
  @Test
  void exactPathMatchesItself() {
    PathTemplate t = PathTemplate.compile("/users");
    assertThat(t.match("/users")).isPresent();
    assertThat(t.match("/users").get()).isEmpty();
  }

  @Test
  void exactPathDoesNotMatchOther() {
    assertThat(PathTemplate.compile("/users").match("/orders")).isEmpty();
  }

  @Test
  void singleParamExtracted() {
    PathTemplate t = PathTemplate.compile("/users/{id}");
    assertThat(t.match("/users/42")).hasValueSatisfying(m -> assertThat(m).containsEntry("id", "42"));
    assertThat(t.parameterNames()).containsExactly("id");
  }

  @Test
  void twoParamsExtracted() {
    PathTemplate t = PathTemplate.compile("/orgs/{org}/repos/{repo}");
    var m = t.match("/orgs/acme/repos/widget").orElseThrow();
    assertThat(m).containsEntry("org", "acme").containsEntry("repo", "widget");
  }

  @Test
  void doesNotMatchSlashesInsideParam() {
    assertThat(PathTemplate.compile("/users/{id}").match("/users/42/foo")).isEmpty();
  }

  @Test
  void rawIsPreserved() {
    assertThat(PathTemplate.compile("/users/{id}").raw()).isEqualTo("/users/{id}");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PathTemplate(String raw, Pattern compiled, List<String> parameterNames) {

  private static final Pattern TOKEN = Pattern.compile("\\{([^/}]+)}");

  public static PathTemplate compile(String template) {
    StringBuilder regex = new StringBuilder("^");
    java.util.List<String> names = new java.util.ArrayList<>();
    Matcher m = TOKEN.matcher(template);
    int last = 0;
    while (m.find()) {
      regex.append(Pattern.quote(template.substring(last, m.start())));
      regex.append("([^/]+)");
      names.add(m.group(1));
      last = m.end();
    }
    regex.append(Pattern.quote(template.substring(last)));
    regex.append("$");
    return new PathTemplate(template, Pattern.compile(regex.toString()), List.copyOf(names));
  }

  public Optional<Map<String, String>> match(String path) {
    Matcher m = compiled.matcher(path);
    if (!m.matches()) return Optional.empty();
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < parameterNames.size(); i++) {
      out.put(parameterNames.get(i), m.group(i + 1));
    }
    return Optional.of(Map.copyOf(out));
  }
}
```

- [ ] **Step 4: Verify** — 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/PathTemplate.java \
        src/test/java/com/retailsvc/http/spec/PathTemplateTest.java
git commit -m "feat(spec): add PathTemplate value object with regex extraction"
```

---

### Task D3: Parameter, RequestBody, MediaType, Response, Server, Info records

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/Parameter.java`
- Create: `src/main/java/com/retailsvc/http/spec/RequestBody.java`
- Create: `src/main/java/com/retailsvc/http/spec/MediaType.java`
- Create: `src/main/java/com/retailsvc/http/spec/Response.java`
- Create: `src/main/java/com/retailsvc/http/spec/Server.java`
- Create: `src/main/java/com/retailsvc/http/spec/Info.java`
- Test: `src/test/java/com/retailsvc/http/spec/SpecRecordsTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecRecordsTest {
  private final Schema s = new BooleanSchema(Set.of(TypeName.BOOLEAN));

  @Test void parameterLocationEnum() {
    Parameter p = new Parameter("x", Parameter.Location.QUERY, true, s);
    assertThat(p.in()).isEqualTo(Parameter.Location.QUERY);
    assertThat(p.required()).isTrue();
  }

  @Test void requestBodyStoresContent() {
    RequestBody body = new RequestBody(true, Map.of("application/json", new MediaType(s)));
    assertThat(body.content()).containsKey("application/json");
    assertThat(body.required()).isTrue();
  }

  @Test void serverHasUrl() {
    assertThat(new Server("http://localhost/api").url()).isEqualTo("http://localhost/api");
  }

  @Test void infoHasTitleAndVersion() {
    Info i = new Info("test", "1.0.0");
    assertThat(i.title()).isEqualTo("test");
    assertThat(i.version()).isEqualTo("1.0.0");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

`Parameter.java`:

```java
package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;

public record Parameter(String name, Location in, boolean required, Schema schema) {
  public enum Location { PATH, QUERY, HEADER, COOKIE }
}
```

`RequestBody.java`:

```java
package com.retailsvc.http.spec;

import java.util.Map;

public record RequestBody(boolean required, Map<String, MediaType> content) {}
```

`MediaType.java`:

```java
package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;

public record MediaType(Schema schema) {}
```

`Response.java` (placeholder; populated when response validation lands):

```java
package com.retailsvc.http.spec;

import java.util.Map;

public record Response(Map<String, MediaType> content) {}
```

`Server.java`:

```java
package com.retailsvc.http.spec;

import java.net.URI;

public record Server(String url) {
  public String basePath() {
    return URI.create(url).getPath();
  }
}
```

`Info.java`:

```java
package com.retailsvc.http.spec;

public record Info(String title, String version) {}
```

- [ ] **Step 4: Verify** — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/ \
        src/test/java/com/retailsvc/http/spec/SpecRecordsTest.java
git commit -m "feat(spec): add Parameter, RequestBody, MediaType, Response, Server, Info records"
```

---

### Task D4: Operation record

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/Operation.java`
- Test: `src/test/java/com/retailsvc/http/spec/OperationTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationTest {
  @Test
  void operationCarriesAllFields() {
    var path = PathTemplate.compile("/users/{id}");
    var param = new Parameter("id", Parameter.Location.PATH, true,
        new BooleanSchema(Set.of(TypeName.BOOLEAN)));
    Operation op = new Operation(
        "get-user", HttpMethod.GET, path, Optional.empty(),
        List.of(param), Map.of());
    assertThat(op.operationId()).isEqualTo("get-user");
    assertThat(op.method()).isEqualTo(HttpMethod.GET);
    assertThat(op.parameters()).hasSize(1);
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

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
    Map<String, Response> responses) {}
```

- [ ] **Step 4: Verify** — 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Operation.java \
        src/test/java/com/retailsvc/http/spec/OperationTest.java
git commit -m "feat(spec): add Operation record"
```

---

### Task D5: Spec record + Spec.from(Map) parser

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/Spec.java`
- Create: `src/main/java/com/retailsvc/http/spec/internal/SpecParser.java` (helpers package-private)
- Test: `src/test/java/com/retailsvc/http/spec/SpecTest.java`
- Resource: existing `src/test/resources/openapi.json` is reused as the canonical fixture.

- [ ] **Step 1: Test against the existing fixture**

```java
package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecTest {
  private final Gson gson = new Gson();

  @SuppressWarnings("unchecked")
  private Map<String, Object> loadJson(String resource) throws Exception {
    String text = new String(
        SpecTest.class.getResourceAsStream("/" + resource).readAllBytes());
    return (Map<String, Object>) gson.fromJson(text, Map.class);
  }

  @Test
  void parsesMinimalSpec() {
    Map<String, Object> raw = Map.of(
        "openapi", "3.1.0",
        "info", Map.of("title", "x", "version", "1"),
        "servers", List.of(Map.of("url", "http://localhost/api")),
        "paths", Map.of());
    Spec spec = Spec.from(raw);
    assertThat(spec.openapi()).isEqualTo("3.1.0");
    assertThat(spec.info().title()).isEqualTo("x");
    assertThat(spec.servers()).hasSize(1);
    assertThat(spec.basePath()).isEqualTo("/api");
    assertThat(spec.operations()).isEmpty();
  }

  @Test
  void parsesPathsWithMethods() {
    Map<String, Object> raw = Map.of(
        "openapi", "3.1.0",
        "info", Map.of("title", "x", "version", "1"),
        "servers", List.of(Map.of("url", "http://localhost")),
        "paths", Map.of(
            "/users", Map.of(
                "get", Map.of("operationId", "list", "responses", Map.of()),
                "post", Map.of("operationId", "create", "responses", Map.of()))));
    Spec spec = Spec.from(raw);
    assertThat(spec.operations()).hasSize(2);
    assertThat(spec.operations().stream().map(Operation::operationId))
        .containsExactlyInAnyOrder("list", "create");
  }

  @Test
  void resolvesSchemaRef() {
    Map<String, Object> raw = Map.of(
        "openapi", "3.1.0",
        "info", Map.of("title", "x", "version", "1"),
        "servers", List.of(Map.of("url", "/")),
        "paths", Map.of(),
        "components", Map.of(
            "schemas", Map.of("User", Map.of("type", "object"))));
    Spec spec = Spec.from(raw);
    assertThat(spec.resolveSchema("#/components/schemas/User"))
        .isInstanceOf(com.retailsvc.http.spec.schema.ObjectSchema.class);
  }

  @Test
  void parsesExistingFixture() throws Exception {
    Spec spec = Spec.from(loadJson("openapi.json"));
    assertThat(spec.operations()).isNotEmpty();
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement Spec + parser**

`Spec.java`:

```java
package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.SchemaParser;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Spec(
    String openapi,
    Info info,
    List<Server> servers,
    List<Operation> operations,
    Map<String, Schema> componentSchemas,
    Map<String, Parameter> componentParameters) {

  @SuppressWarnings("unchecked")
  public static Spec from(Map<String, Object> raw) {
    String openapi = (String) raw.get("openapi");
    Info info = parseInfo((Map<String, Object>) raw.get("info"));
    List<Server> servers = parseServers((List<Map<String, Object>>) raw.get("servers"));
    Map<String, Object> rawComponents = (Map<String, Object>) raw.getOrDefault("components", Map.of());
    Map<String, Schema> componentSchemas = parseComponentSchemas(rawComponents);
    Map<String, Parameter> componentParameters = parseComponentParameters(rawComponents);
    List<Operation> operations = parseOperations(
        (Map<String, Object>) raw.getOrDefault("paths", Map.of()));
    return new Spec(openapi, info, servers, operations, componentSchemas, componentParameters);
  }

  public String basePath() {
    if (servers.isEmpty()) {
      throw new IllegalStateException("no servers declared");
    }
    return Optional.ofNullable(URI.create(servers.get(0).url()).getPath()).orElse("");
  }

  public Schema resolveSchema(String ref) {
    String name = stripPrefix(ref, "#/components/schemas/");
    Schema s = componentSchemas.get(name);
    if (s == null) throw new IllegalArgumentException("unknown schema ref: " + ref);
    return s;
  }

  public Parameter resolveParameter(String ref) {
    String name = stripPrefix(ref, "#/components/parameters/");
    Parameter p = componentParameters.get(name);
    if (p == null) throw new IllegalArgumentException("unknown parameter ref: " + ref);
    return p;
  }

  private static String stripPrefix(String ref, String prefix) {
    if (!ref.startsWith(prefix)) {
      throw new IllegalArgumentException("ref does not start with " + prefix + ": " + ref);
    }
    return ref.substring(prefix.length());
  }

  private static Info parseInfo(Map<String, Object> raw) {
    return new Info((String) raw.get("title"), (String) raw.get("version"));
  }

  private static List<Server> parseServers(List<Map<String, Object>> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    return raw.stream().map(m -> new Server((String) m.get("url"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Schema> parseComponentSchemas(Map<String, Object> rawComponents) {
    Map<String, Object> rawSchemas = (Map<String, Object>) rawComponents.getOrDefault("schemas", Map.of());
    Map<String, Schema> out = new LinkedHashMap<>();
    for (var e : rawSchemas.entrySet()) {
      out.put(e.getKey(), SchemaParser.parse((Map<String, Object>) e.getValue()));
    }
    return Map.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Parameter> parseComponentParameters(Map<String, Object> rawComponents) {
    Map<String, Object> rawParams = (Map<String, Object>) rawComponents.getOrDefault("parameters", Map.of());
    Map<String, Parameter> out = new LinkedHashMap<>();
    for (var e : rawParams.entrySet()) {
      out.put(e.getKey(), parseParameter((Map<String, Object>) e.getValue()));
    }
    return Map.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Parameter parseParameter(Map<String, Object> raw) {
    return new Parameter(
        (String) raw.get("name"),
        Parameter.Location.valueOf(((String) raw.get("in")).toUpperCase(java.util.Locale.ROOT)),
        Boolean.TRUE.equals(raw.get("required")),
        SchemaParser.parse((Map<String, Object>) raw.getOrDefault("schema", Map.of("type", "string"))));
  }

  @SuppressWarnings("unchecked")
  private static List<Operation> parseOperations(Map<String, Object> rawPaths) {
    List<Operation> out = new java.util.ArrayList<>();
    for (var pathEntry : rawPaths.entrySet()) {
      PathTemplate template = PathTemplate.compile(pathEntry.getKey());
      Map<String, Object> pathItem = (Map<String, Object>) pathEntry.getValue();
      for (HttpMethod m : HttpMethod.values()) {
        Object opRaw = pathItem.get(m.name().toLowerCase(java.util.Locale.ROOT));
        if (opRaw instanceof Map<?, ?> opMap) {
          out.add(parseOperation(m, template, (Map<String, Object>) opMap));
        }
      }
    }
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Operation parseOperation(HttpMethod method, PathTemplate path, Map<String, Object> raw) {
    String opId = (String) raw.get("operationId");
    Optional<RequestBody> body = Optional.ofNullable((Map<String, Object>) raw.get("requestBody"))
        .map(Spec::parseRequestBody);
    List<Parameter> params = Optional.ofNullable((List<Map<String, Object>>) raw.get("parameters"))
        .map(list -> list.stream().map(Spec::parseParameter).toList())
        .orElse(List.of());
    Map<String, Response> responses = parseResponses(
        (Map<String, Object>) raw.getOrDefault("responses", Map.of()));
    return new Operation(opId, method, path, body, params, responses);
  }

  @SuppressWarnings("unchecked")
  private static RequestBody parseRequestBody(Map<String, Object> raw) {
    Map<String, Object> contentRaw = (Map<String, Object>) raw.getOrDefault("content", Map.of());
    Map<String, MediaType> content = new LinkedHashMap<>();
    for (var e : contentRaw.entrySet()) {
      Map<String, Object> mt = (Map<String, Object>) e.getValue();
      content.put(e.getKey(), new MediaType(SchemaParser.parse(
          (Map<String, Object>) mt.getOrDefault("schema", Map.of("type", "object")))));
    }
    return new RequestBody(Boolean.TRUE.equals(raw.get("required")), Map.copyOf(content));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Response> parseResponses(Map<String, Object> raw) {
    Map<String, Response> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      Map<String, Object> r = (Map<String, Object>) e.getValue();
      Map<String, Object> contentRaw = (Map<String, Object>) r.getOrDefault("content", Map.of());
      Map<String, MediaType> content = new LinkedHashMap<>();
      for (var ce : contentRaw.entrySet()) {
        Map<String, Object> mt = (Map<String, Object>) ce.getValue();
        if (mt.containsKey("schema")) {
          content.put(ce.getKey(), new MediaType(SchemaParser.parse(
              (Map<String, Object>) mt.get("schema"))));
        }
      }
      out.put(e.getKey(), new Response(Map.copyOf(content)));
    }
    return Map.copyOf(out);
  }
}
```

- [ ] **Step 4: Verify** — 4 PASS, full suite still green.

Run: `mvn -q test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Spec.java \
        src/test/java/com/retailsvc/http/spec/SpecTest.java
git commit -m "feat(spec): add Spec.from(Map) walker for the full document"
```

---

## Phase E — Validator

### Task E1: ValidationError + ValidationException + Validator interface

**Files:**
- Create: `src/main/java/com/retailsvc/http/validate/ValidationError.java`
- Create: `src/main/java/com/retailsvc/http/validate/Validator.java`
- Create: `src/main/java/com/retailsvc/http/ValidationException.java`
- Test: `src/test/java/com/retailsvc/http/ValidationExceptionTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.validate.ValidationError;
import org.junit.jupiter.api.Test;

class ValidationExceptionTest {
  @Test
  void carriesError() {
    ValidationError e = new ValidationError("/x", "type", "expected string", null);
    ValidationException ex = new ValidationException(e);
    assertThat(ex.error()).isSameAs(e);
    assertThat(ex.getMessage()).contains("/x").contains("type").contains("expected string");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

`ValidationError.java`:

```java
package com.retailsvc.http.validate;

public record ValidationError(String pointer, String keyword, String message, Object rejectedValue) {}
```

`Validator.java`:

```java
package com.retailsvc.http.validate;

import com.retailsvc.http.spec.schema.Schema;

public interface Validator {
  /** Throws ValidationException on first failure. */
  void validate(Object value, Schema schema, String pointer);
}
```

`ValidationException.java`:

```java
package com.retailsvc.http;

import com.retailsvc.http.validate.ValidationError;

public final class ValidationException extends RuntimeException {
  private final ValidationError error;

  public ValidationException(ValidationError error) {
    super(error.pointer() + " [" + error.keyword() + "] " + error.message());
    this.error = error;
  }

  public ValidationError error() { return error; }
}
```

- [ ] **Step 4: Verify** — 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/ \
        src/main/java/com/retailsvc/http/ValidationException.java \
        src/test/java/com/retailsvc/http/ValidationExceptionTest.java
git commit -m "feat(validate): add ValidationError, ValidationException, Validator interface"
```

---

### Task E2: DefaultValidator skeleton + null/boolean/ref dispatch

**Files:**
- Create: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Test: `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultValidatorDispatchTest {
  private final Validator v = new DefaultValidator(name -> { throw new AssertionError("no refs"); });

  @Test
  void nullSchemaAcceptsNull() {
    v.validate(null, new NullSchema(), "");
  }

  @Test
  void nullSchemaRejectsNonNull() {
    assertThatThrownBy(() -> v.validate("x", new NullSchema(), "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void booleanSchemaAcceptsBoolean() {
    v.validate(true, new BooleanSchema(Set.of(TypeName.BOOLEAN)), "/v");
  }

  @Test
  void booleanSchemaRejectsString() {
    assertThatThrownBy(() -> v.validate("x", new BooleanSchema(Set.of(TypeName.BOOLEAN)), "/v"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void combinatorThrowsUnsupported() {
    assertThatThrownBy(() -> v.validate("x", new OneOfSchema(List.of()), "/v"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement skeleton**

```java
package com.retailsvc.http.validate;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.*;
import java.util.function.Function;

public final class DefaultValidator implements Validator {
  private final Function<String, Schema> refResolver;

  public DefaultValidator(Function<String, Schema> refResolver) {
    this.refResolver = refResolver;
  }

  @Override
  public void validate(Object value, Schema schema, String pointer) {
    if (value == null && schema.types().contains(TypeName.NULL)) return;

    switch (schema) {
      case RefSchema r       -> validate(value, refResolver.apply(r.pointer()), pointer);
      case BooleanSchema b   -> validateBoolean(value, pointer);
      case NullSchema n      -> require(value == null, pointer, "type", "expected null");
      case StringSchema s    -> validateString(value, s, pointer);
      case IntegerSchema i   -> validateInteger(value, i, pointer);
      case NumberSchema n    -> validateNumber(value, n, pointer);
      case ObjectSchema o    -> validateObject(value, o, pointer);
      case ArraySchema a     -> validateArray(value, a, pointer);
      case EnumSchema e      -> require(e.values().contains(value), pointer, "enum", "value not in enum");
      case ConstSchema c     -> require(java.util.Objects.equals(c.value(), value), pointer, "const", "value does not equal const");
      case OneOfSchema o     -> throw new UnsupportedOperationException("oneOf not yet supported");
      case AnyOfSchema a     -> throw new UnsupportedOperationException("anyOf not yet supported");
      case AllOfSchema a     -> throw new UnsupportedOperationException("allOf not yet supported");
      case NotSchema n       -> throw new UnsupportedOperationException("not not yet supported");
    }
  }

  private void validateBoolean(Object value, String pointer) {
    require(value instanceof Boolean, pointer, "type", "expected boolean");
  }

  private void validateString(Object value, StringSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements string");
  }

  private void validateInteger(Object value, IntegerSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements integer");
  }

  private void validateNumber(Object value, NumberSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements number");
  }

  private void validateObject(Object value, ObjectSchema s, String pointer) {
    throw new UnsupportedOperationException("E4 implements object");
  }

  private void validateArray(Object value, ArraySchema s, String pointer) {
    throw new UnsupportedOperationException("E4 implements array");
  }

  static void require(boolean condition, String pointer, String keyword, String message) {
    if (!condition) {
      throw new ValidationException(new ValidationError(pointer, keyword, message, null));
    }
  }
}
```

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest` — 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java
git commit -m "feat(validate): DefaultValidator skeleton with dispatch + boolean/null/ref/enum/const"
```

---

### Task E3: String, integer, number validation bodies

**Files:**
- Modify: `DefaultValidator.java`
- Create: `src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StringIntegerNumberTest {
  private final Validator v = new DefaultValidator(name -> { throw new AssertionError(); });

  @Test
  void stringMinLength() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null);
    assertThatCode(() -> v.validate("abc", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("ab", s, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("minLength");
  }

  @Test
  void stringMaxLength() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, 5, null, null);
    assertThatThrownBy(() -> v.validate("abcdef", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("maxLength");
  }

  @Test
  void stringPattern() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), "^[a-z]+$", null, null, null, null);
    assertThatCode(() -> v.validate("abc", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("ABC", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("pattern");
  }

  @Test
  void stringEnum() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, null, List.of("a", "b"));
    assertThatCode(() -> v.validate("a", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("c", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("enum");
  }

  @Test
  void stringFormatUuid() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "uuid", null);
    assertThatCode(() -> v.validate(java.util.UUID.randomUUID().toString(), s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("not-a-uuid", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("format");
  }

  @Test
  void integerWithMinMax() {
    IntegerSchema s = new IntegerSchema(Set.of(TypeName.INTEGER), 0L, 10L, null, null, null, "int32");
    assertThatCode(() -> v.validate(5, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(-1, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("minimum");
    assertThatThrownBy(() -> v.validate(11, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("maximum");
  }

  @Test
  void integerExclusiveBoundsBugFixedFromMaster() {
    // Master's Schema defaulted minimum to Double.MIN_VALUE (~4.9e-324) and silently rejected
    // negative numbers. New model uses null = no constraint.
    IntegerSchema s = new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32");
    assertThatCode(() -> v.validate(-1_000_000, s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void integerMultipleOf() {
    IntegerSchema s = new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, 5L, "int32");
    assertThatCode(() -> v.validate(15, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(7, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("multipleOf");
  }

  @Test
  void numberAcceptsDoublesAndIntegers() {
    NumberSchema s = new NumberSchema(Set.of(TypeName.NUMBER), 0, 1, null, null, null, "double");
    assertThatCode(() -> v.validate(0.5, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(1, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(2.0, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("maximum");
  }

  @Test
  void stringRejectsNonString() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null);
    assertThatThrownBy(() -> v.validate(42, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("type");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Replace stub bodies**

```java
  private void validateString(Object value, StringSchema s, String pointer) {
    require(value instanceof String, pointer, "type", "expected string");
    String str = (String) value;
    if (s.minLength() != null && str.length() < s.minLength())
      fail(pointer, "minLength", "string shorter than " + s.minLength(), str);
    if (s.maxLength() != null && str.length() > s.maxLength())
      fail(pointer, "maxLength", "string longer than " + s.maxLength(), str);
    if (s.pattern() != null && !java.util.regex.Pattern.compile(s.pattern()).matcher(str).matches())
      fail(pointer, "pattern", "does not match pattern " + s.pattern(), str);
    if (s.enumValues() != null && !s.enumValues().contains(str))
      fail(pointer, "enum", "value not in enum", str);
    if (s.format() != null) validateStringFormat(str, s.format(), pointer);
  }

  private void validateStringFormat(String str, String format, String pointer) {
    switch (format) {
      case "uuid" -> {
        try { java.util.UUID.fromString(str); }
        catch (IllegalArgumentException e) { fail(pointer, "format", "not a valid uuid", str); }
      }
      case "date" -> {
        try { java.time.LocalDate.parse(str); }
        catch (Exception e) { fail(pointer, "format", "not a valid date", str); }
      }
      case "date-time" -> {
        try { java.time.OffsetDateTime.parse(str); }
        catch (Exception e) { fail(pointer, "format", "not a valid date-time", str); }
      }
      default -> { /* unknown format ignored */ }
    }
  }

  private void validateInteger(Object value, IntegerSchema s, String pointer) {
    long n;
    if (value instanceof Number num) n = num.longValue();
    else if (value instanceof String str) {
      try { n = Long.parseLong(str); }
      catch (NumberFormatException e) { fail(pointer, "type", "expected integer", value); return; }
    }
    else { fail(pointer, "type", "expected integer", value); return; }

    if (s.minimum() != null && n < s.minimum())
      fail(pointer, "minimum", "integer below minimum " + s.minimum(), n);
    if (s.maximum() != null && n > s.maximum())
      fail(pointer, "maximum", "integer above maximum " + s.maximum(), n);
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum())
      fail(pointer, "exclusiveMinimum", "integer not greater than " + s.exclusiveMinimum(), n);
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum())
      fail(pointer, "exclusiveMaximum", "integer not less than " + s.exclusiveMaximum(), n);
    if (s.multipleOf() != null && n % s.multipleOf() != 0)
      fail(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
  }

  private void validateNumber(Object value, NumberSchema s, String pointer) {
    double n;
    if (value instanceof Number num) n = num.doubleValue();
    else if (value instanceof String str) {
      try { n = Double.parseDouble(str); }
      catch (NumberFormatException e) { fail(pointer, "type", "expected number", value); return; }
    }
    else { fail(pointer, "type", "expected number", value); return; }

    if (s.minimum() != null && n < s.minimum().doubleValue())
      fail(pointer, "minimum", "number below minimum " + s.minimum(), n);
    if (s.maximum() != null && n > s.maximum().doubleValue())
      fail(pointer, "maximum", "number above maximum " + s.maximum(), n);
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum().doubleValue())
      fail(pointer, "exclusiveMinimum", "number not greater than " + s.exclusiveMinimum(), n);
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum().doubleValue())
      fail(pointer, "exclusiveMaximum", "number not less than " + s.exclusiveMaximum(), n);
    if (s.multipleOf() != null && (n / s.multipleOf().doubleValue()) % 1 != 0)
      fail(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
  }

  private static void fail(String pointer, String keyword, String message, Object rejectedValue) {
    throw new ValidationException(new ValidationError(pointer, keyword, message, rejectedValue));
  }
```

(Remove `private void validateString/Integer/Number` UnsupportedOperationException stubs.)

- [ ] **Step 4: Verify**

Run: `mvn -q test -Dtest=StringIntegerNumberTest` — 10 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/StringIntegerNumberTest.java
git commit -m "feat(validate): string/integer/number validation with full 3.1 numeric keywords"
```

---

### Task E4: Object validation (required, properties, additionalProperties, sizes)

**Files:**
- Modify: `DefaultValidator.java`
- Create: `src/test/java/com/retailsvc/http/validate/ObjectValidationTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObjectValidationTest {
  private final Validator v = new DefaultValidator(name -> { throw new AssertionError(); });

  private ObjectSchema obj(Map<String, Schema> props, List<String> required, AdditionalProperties ap) {
    return new ObjectSchema(Set.of(TypeName.OBJECT), props, required, ap, null, null);
  }

  @Test
  void requiredFieldMissing() {
    var s = obj(Map.of("name", new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null)),
                List.of("name"), new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate(Map.of(), s, ""))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("required");
  }

  @Test
  void propertyValidatedAtPointer() {
    var s = obj(Map.of("name", new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null)),
                List.of(), new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate(Map.of("name", "ab"), s, ""))
        .extracting(t -> ((ValidationException) t).error().pointer()).isEqualTo("/name");
  }

  @Test
  void additionalPropertiesAllowedByDefault() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Allowed());
    assertThatCode(() -> v.validate(Map.of("extra", "x"), s, "")).doesNotThrowAnyException();
  }

  @Test
  void additionalPropertiesForbidden() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Forbidden());
    assertThatThrownBy(() -> v.validate(Map.of("extra", "x"), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("additionalProperties");
  }

  @Test
  void rejectsNonObject() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate("nope", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("type");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

Replace stub:

```java
  @SuppressWarnings("unchecked")
  private void validateObject(Object value, ObjectSchema s, String pointer) {
    require(value instanceof Map, pointer, "type", "expected object");
    Map<String, Object> map = (Map<String, Object>) value;

    for (String required : s.required()) {
      require(map.containsKey(required), pointer + "/" + required, "required", "required property missing");
    }

    if (s.minProperties() != null && map.size() < s.minProperties())
      fail(pointer, "minProperties", "fewer than " + s.minProperties() + " properties", map.size());
    if (s.maxProperties() != null && map.size() > s.maxProperties())
      fail(pointer, "maxProperties", "more than " + s.maxProperties() + " properties", map.size());

    for (var entry : map.entrySet()) {
      String childPointer = pointer + "/" + entry.getKey();
      Schema propSchema = s.properties().get(entry.getKey());
      if (propSchema != null) {
        validate(entry.getValue(), propSchema, childPointer);
      } else {
        switch (s.additionalProperties()) {
          case AdditionalProperties.Allowed a -> {}
          case AdditionalProperties.Forbidden f ->
              fail(childPointer, "additionalProperties", "additional property not allowed", entry.getKey());
          case AdditionalProperties.SchemaConstraint sc ->
              validate(entry.getValue(), sc.schema(), childPointer);
        }
      }
    }
  }
```

- [ ] **Step 4: Verify** — 5 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/ObjectValidationTest.java
git commit -m "feat(validate): object validation with required/properties/additionalProperties"
```

---

### Task E5: Array validation (items, minItems/maxItems, uniqueItems)

**Files:**
- Modify: `DefaultValidator.java`
- Create: `src/test/java/com/retailsvc/http/validate/ArrayValidationTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArrayValidationTest {
  private final Validator v = new DefaultValidator(name -> { throw new AssertionError(); });

  private ArraySchema arr(Schema item, Integer minI, Integer maxI, boolean unique) {
    return new ArraySchema(Set.of(TypeName.ARRAY), item, minI, maxI, unique);
  }

  @Test
  void itemsValidated() {
    var s = arr(new IntegerSchema(Set.of(TypeName.INTEGER), 0L, 100L, null, null, null, "int32"),
                null, null, false);
    assertThatCode(() -> v.validate(List.of(1, 2, 3), s, "")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(List.of(1, -1), s, ""))
        .extracting(t -> ((ValidationException) t).error().pointer()).isEqualTo("/1");
  }

  @Test
  void minItemsEnforced() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), 2, null, false);
    assertThatThrownBy(() -> v.validate(List.of(true), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("minItems");
  }

  @Test
  void maxItemsEnforced() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), null, 1, false);
    assertThatThrownBy(() -> v.validate(List.of(true, false), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("maxItems");
  }

  @Test
  void uniqueItemsEnforced() {
    var s = arr(new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32"),
                null, null, true);
    assertThatThrownBy(() -> v.validate(List.of(1, 2, 1), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("uniqueItems");
  }

  @Test
  void rejectsNonIterable() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), null, null, false);
    assertThatThrownBy(() -> v.validate("nope", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword()).isEqualTo("type");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
  private void validateArray(Object value, ArraySchema s, String pointer) {
    require(value instanceof Iterable, pointer, "type", "expected array");
    Iterable<?> it = (Iterable<?>) value;
    java.util.List<Object> elements = new java.util.ArrayList<>();
    for (Object o : it) elements.add(o);

    if (s.minItems() != null && elements.size() < s.minItems())
      fail(pointer, "minItems", "fewer than " + s.minItems() + " items", elements.size());
    if (s.maxItems() != null && elements.size() > s.maxItems())
      fail(pointer, "maxItems", "more than " + s.maxItems() + " items", elements.size());

    if (s.uniqueItems()) {
      java.util.Set<Object> seen = new java.util.HashSet<>();
      for (Object e : elements) {
        if (!seen.add(e)) fail(pointer, "uniqueItems", "duplicate item", e);
      }
    }

    for (int i = 0; i < elements.size(); i++) {
      validate(elements.get(i), s.items(), pointer + "/" + i);
    }
  }
```

- [ ] **Step 4: Verify** — 5 PASS, full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/ArrayValidationTest.java
git commit -m "feat(validate): array validation with items/minItems/maxItems/uniqueItems"
```

---

## Phase F — Routing

### Task F1: Router

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/Router.java`
- Test: `src/test/java/com/retailsvc/http/internal/RouterTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.PathTemplate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouterTest {
  private Operation op(String id, HttpMethod m, String path) {
    return new Operation(id, m, PathTemplate.compile(path), Optional.empty(), List.of(), Map.of());
  }

  @Test
  void exactPathMatchByMethod() {
    Router r = new Router(List.of(op("a", HttpMethod.GET, "/users"), op("b", HttpMethod.POST, "/users")));
    assertThat(r.match(HttpMethod.GET, "/users").orElseThrow().operation().operationId()).isEqualTo("a");
    assertThat(r.match(HttpMethod.POST, "/users").orElseThrow().operation().operationId()).isEqualTo("b");
  }

  @Test
  void templatedPathExtractsParam() {
    Router r = new Router(List.of(op("g", HttpMethod.GET, "/users/{id}")));
    Router.Match m = r.match(HttpMethod.GET, "/users/42").orElseThrow();
    assertThat(m.operation().operationId()).isEqualTo("g");
    assertThat(m.pathParameters()).containsEntry("id", "42");
  }

  @Test
  void unknownPathReturnsEmpty() {
    Router r = new Router(List.of(op("g", HttpMethod.GET, "/users")));
    assertThat(r.match(HttpMethod.GET, "/orders")).isEmpty();
  }

  @Test
  void allowedMethodsForKnownPath() {
    Router r = new Router(List.of(
        op("a", HttpMethod.GET, "/users"),
        op("b", HttpMethod.POST, "/users")));
    assertThat(r.allowedMethods("/users")).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    assertThat(r.allowedMethods("/missing")).isEmpty();
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.PathTemplate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Router {

  public record Match(Operation operation, Map<String, String> pathParameters) {}

  private final Map<HttpMethod, Map<String, Operation>> exact = new EnumMap<>(HttpMethod.class);
  private final Map<HttpMethod, List<Operation>> templated = new EnumMap<>(HttpMethod.class);

  public Router(List<Operation> operations) {
    for (HttpMethod m : HttpMethod.values()) {
      exact.put(m, new LinkedHashMap<>());
      templated.put(m, new java.util.ArrayList<>());
    }
    for (Operation op : operations) {
      if (op.path().parameterNames().isEmpty()) {
        exact.get(op.method()).put(op.path().raw(), op);
      } else {
        templated.get(op.method()).add(op);
      }
    }
  }

  public Optional<Match> match(HttpMethod method, String path) {
    Operation hit = exact.get(method).get(path);
    if (hit != null) return Optional.of(new Match(hit, Map.of()));
    for (Operation op : templated.get(method)) {
      Optional<Map<String, String>> params = op.path().match(path);
      if (params.isPresent()) return Optional.of(new Match(op, params.get()));
    }
    return Optional.empty();
  }

  public Set<HttpMethod> allowedMethods(String path) {
    java.util.EnumSet<HttpMethod> out = java.util.EnumSet.noneOf(HttpMethod.class);
    for (HttpMethod m : HttpMethod.values()) {
      if (exact.get(m).containsKey(path)) { out.add(m); continue; }
      for (Operation op : templated.get(m)) {
        if (op.path().match(path).isPresent()) { out.add(m); break; }
      }
    }
    return out;
  }
}
```

- [ ] **Step 4: Verify** — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/Router.java \
        src/test/java/com/retailsvc/http/internal/RouterTest.java
git commit -m "feat(internal): Router with exact and templated indexes plus allowedMethods()"
```

---

## Phase G — Public API surface (exceptions, helpers, JsonMapper)

### Task G1: NotFoundException + MethodNotAllowedException

**Files:**
- Create: `src/main/java/com/retailsvc/http/NotFoundException.java`
- Create: `src/main/java/com/retailsvc/http/MethodNotAllowedException.java`
- Test: `src/test/java/com/retailsvc/http/HttpExceptionsTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.spec.HttpMethod;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpExceptionsTest {
  @Test void notFoundCarriesPath() {
    NotFoundException e = new NotFoundException("GET /missing");
    assertThat(e.getMessage()).isEqualTo("GET /missing");
  }

  @Test void methodNotAllowedCarriesAllowedSet() {
    MethodNotAllowedException e = new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST));
    assertThat(e.allowed()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http;

public final class NotFoundException extends RuntimeException {
  public NotFoundException(String message) { super(message); }
}
```

```java
package com.retailsvc.http;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Set;

public final class MethodNotAllowedException extends RuntimeException {
  private final Set<HttpMethod> allowed;
  public MethodNotAllowedException(Set<HttpMethod> allowed) {
    super("method not allowed; allowed=" + allowed);
    this.allowed = Set.copyOf(allowed);
  }
  public Set<HttpMethod> allowed() { return allowed; }
}
```

- [ ] **Step 4: Verify** — 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/NotFoundException.java \
        src/main/java/com/retailsvc/http/MethodNotAllowedException.java \
        src/test/java/com/retailsvc/http/HttpExceptionsTest.java
git commit -m "feat(http): add NotFoundException and MethodNotAllowedException"
```

---

### Task G2: Request helper

**Files:**
- Create: `src/main/java/com/retailsvc/http/Request.java`
- Test: `src/test/java/com/retailsvc/http/RequestTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RequestTest {
  @Test
  void readsAttributes() {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getAttribute("body")).thenReturn(new byte[]{1, 2, 3});
    Mockito.when(ex.getAttribute("parsed-body")).thenReturn(Map.of("k", "v"));
    Mockito.when(ex.getAttribute("operation-id")).thenReturn("get-x");
    Mockito.when(ex.getAttribute("path-parameters")).thenReturn(Map.of("id", "42"));

    assertThat(Request.bytes(ex)).containsExactly(1, 2, 3);
    assertThat(Request.parsed(ex)).isEqualTo(Map.of("k", "v"));
    assertThat(Request.operationId(ex)).isEqualTo("get-x");
    assertThat(Request.pathParams(ex)).containsEntry("id", "42");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

public final class Request {
  public static final String BODY            = "body";
  public static final String PARSED_BODY     = "parsed-body";
  public static final String OPERATION_ID    = "operation-id";
  public static final String PATH_PARAMETERS = "path-parameters";

  private Request() {}

  public static byte[] bytes(HttpExchange e)         { return (byte[]) e.getAttribute(BODY); }
  public static Object parsed(HttpExchange e)        { return e.getAttribute(PARSED_BODY); }
  public static String operationId(HttpExchange e)   { return (String) e.getAttribute(OPERATION_ID); }
  @SuppressWarnings("unchecked")
  public static Map<String, String> pathParams(HttpExchange e) {
    return (Map<String, String>) e.getAttribute(PATH_PARAMETERS);
  }
}
```

- [ ] **Step 4: Verify** — 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/Request.java \
        src/test/java/com/retailsvc/http/RequestTest.java
git commit -m "feat(http): add Request static accessors for exchange attributes"
```

---

### Task G3: Reduce JsonMapper to single-method functional interface

**Files:**
- Modify: `src/main/java/com/retailsvc/http/openapi/model/JsonMapper.java` → moved to new package & shape
- Create: `src/main/java/com/retailsvc/http/JsonMapper.java`
- Test: `src/test/java/com/retailsvc/http/JsonMapperTest.java`

The old `JsonMapper` lives at `com.retailsvc.http.openapi.model.JsonMapper` with a generic single method `<T> T mapFrom(byte[] body)`. We add the new shape in the public package now; the old one stays until Phase K deletes it.

- [ ] **Step 1: Test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class JsonMapperTest {
  @Test
  void usableAsLambda() {
    JsonMapper m = body -> new String(body);
    assertThat(m.mapFrom("hello".getBytes())).isEqualTo("hello");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http;

@FunctionalInterface
public interface JsonMapper {
  Object mapFrom(byte[] body);
}
```

- [ ] **Step 4: Verify** — 1 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/JsonMapper.java \
        src/test/java/com/retailsvc/http/JsonMapperTest.java
git commit -m "feat(http): JsonMapper SAM in public package (no generic)"
```

---

### Task G4: ProblemDetailRenderer + updated default ExceptionHandler

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java`
- Modify: `src/main/java/com/retailsvc/http/Handlers.java` (add new branches; keep notFoundHandler)
- Test: `src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java`
- Test: `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java`

- [ ] **Step 1: Test renderer**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.validate.ValidationError;
import org.junit.jupiter.api.Test;

class ProblemDetailRendererTest {
  @Test
  void rendersExpectedFields() {
    String body = ProblemDetailRenderer.render(
        new ValidationError("/email", "format", "string does not match format 'email'", null));
    assertThat(body)
        .contains("\"type\":\"about:blank\"")
        .contains("\"title\":\"Bad Request\"")
        .contains("\"status\":400")
        .contains("\"pointer\":\"/email\"")
        .contains("\"keyword\":\"format\"")
        .contains("\"detail\":\"string does not match format 'email'\"");
  }

  @Test
  void escapesQuotesInDetail() {
    String body = ProblemDetailRenderer.render(
        new ValidationError("/x", "k", "has \"quotes\"", null));
    assertThat(body).contains("\"detail\":\"has \\\"quotes\\\"\"");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement renderer**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.validate.ValidationError;

public final class ProblemDetailRenderer {
  private ProblemDetailRenderer() {}

  public static String render(ValidationError error) {
    return "{"
        + "\"type\":\"about:blank\","
        + "\"title\":\"Bad Request\","
        + "\"status\":400,"
        + "\"detail\":\"" + escape(error.message()) + "\","
        + "\"pointer\":\"" + escape(error.pointer()) + "\","
        + "\"keyword\":\"" + escape(error.keyword()) + "\""
        + "}";
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"'  -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.toString();
  }
}
```

- [ ] **Step 4: Verify renderer** — 2 PASS.

- [ ] **Step 5: Test default exception handler**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.validate.ValidationError;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HandlersDefaultExceptionTest {
  private HttpExchange newExchange(ByteArrayOutputStream sink) {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getResponseHeaders()).thenReturn(new Headers());
    Mockito.when(ex.getResponseBody()).thenReturn(sink);
    return ex;
  }

  @Test
  void validationExceptionRendersProblem() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    HttpExchange ex = newExchange(sink);

    Handlers.defaultExceptionHandler().handle(ex,
        new ValidationException(new ValidationError("/x", "type", "expected string", null)));

    Mockito.verify(ex).sendResponseHeaders(Mockito.eq(400), Mockito.anyLong());
    assertThat(ex.getResponseHeaders().getFirst("Content-Type"))
        .isEqualTo("application/problem+json");
    assertThat(sink.toString()).contains("\"keyword\":\"type\"");
  }

  @Test
  void notFoundReturns404() throws Exception {
    HttpExchange ex = newExchange(new ByteArrayOutputStream());
    Handlers.defaultExceptionHandler().handle(ex, new NotFoundException("GET /x"));
    Mockito.verify(ex).sendResponseHeaders(404, 0);
  }

  @Test
  void methodNotAllowedReturns405WithAllowHeader() throws Exception {
    HttpExchange ex = newExchange(new ByteArrayOutputStream());
    Handlers.defaultExceptionHandler().handle(ex,
        new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST)));
    Mockito.verify(ex).sendResponseHeaders(405, 0);
    assertThat(ex.getResponseHeaders().getFirst("Allow")).contains("GET").contains("POST");
  }
}
```

- [ ] **Step 6: Run — fails (default handler not yet handling new types)**

- [ ] **Step 7: Implement** in `Handlers.java`

Replace the existing `Handlers.java` body. Keep `notFoundHandler()` unchanged. Update `defaultExceptionHandler()` to:

```java
package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.retailsvc.http.internal.ProblemDetailRenderer;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);

  private Handlers() {}

  public static ExceptionHandler defaultExceptionHandler() {
    return (exchange, t) -> {
      try (exchange) {
        switch (t) {
          case ValidationException ve -> {
            byte[] body = ProblemDetailRenderer.render(ve.error()).getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(HTTP_BAD_REQUEST, body.length);
            exchange.getResponseBody().write(body);
          }
          case NotFoundException nf -> exchange.sendResponseHeaders(HTTP_NOT_FOUND, 0);
          case MethodNotAllowedException mna -> {
            String allow = mna.allowed().stream()
                .map(Enum::name).collect(Collectors.joining(", "));
            exchange.getResponseHeaders().add("Allow", allow);
            exchange.sendResponseHeaders(HTTP_BAD_METHOD, 0);
          }
          default -> {
            LOG.error("Unhandled exception in handler", t);
            exchange.sendResponseHeaders(HTTP_INTERNAL_ERROR, 0);
          }
        }
      } catch (IOException io) {
        LOG.error("Failed writing error response", io);
      }
    };
  }

  public static HttpHandler notFoundHandler() {
    return exchange -> {
      try (exchange) {
        exchange.sendResponseHeaders(HTTP_NOT_FOUND, 0);
      }
    };
  }
}
```

`ExceptionHandler` interface stays where it is today (`com.retailsvc.http.ExceptionHandler`); no change needed.

- [ ] **Step 8: Verify**

Run: `mvn -q test -Dtest=HandlersDefaultExceptionTest,ProblemDetailRendererTest` — 5 PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java \
        src/main/java/com/retailsvc/http/Handlers.java \
        src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java \
        src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java
git commit -m "feat(http): RFC 7807 problem+json renderer + default handler covers new types"
```

---

## Phase H — Internal filters

### Task H1: ExceptionFilter

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ExceptionFilter.java`
- Test: `src/test/java/com/retailsvc/http/internal/ExceptionFilterTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.NotFoundException;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ExceptionFilterTest {
  @Test
  void delegatesToExceptionHandler() throws Exception {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    ExceptionHandler handler = Mockito.mock(ExceptionHandler.class);
    Filter f = new ExceptionFilter(handler);
    Filter.Chain chain = Mockito.mock(Filter.Chain.class);
    Mockito.doThrow(new NotFoundException("x")).when(chain).doFilter(ex);
    f.doFilter(ex, chain);
    Mockito.verify(handler).handle(Mockito.eq(ex), Mockito.any(NotFoundException.class));
  }

  @Test
  void passThroughOnSuccess() throws Exception {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    ExceptionHandler handler = Mockito.mock(ExceptionHandler.class);
    Filter f = new ExceptionFilter(handler);
    Filter.Chain chain = Mockito.mock(Filter.Chain.class);
    f.doFilter(ex, chain);
    Mockito.verify(chain).doFilter(ex);
    Mockito.verifyNoInteractions(handler);
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class ExceptionFilter extends Filter {
  private final ExceptionHandler handler;

  public ExceptionFilter(ExceptionHandler handler) {
    this.handler = handler;
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      handler.handle(exchange, t);
    }
  }

  @Override
  public String description() { return "Exception filter"; }
}
```

- [ ] **Step 4: Verify** — 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ExceptionFilter.java \
        src/test/java/com/retailsvc/http/internal/ExceptionFilterTest.java
git commit -m "feat(internal): ExceptionFilter delegates to consumer ExceptionHandler"
```

---

### Task H2: RequestPreparationFilter

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`
- Test: `src/test/java/com/retailsvc/http/internal/RequestPreparationFilterTest.java`

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.JsonMapper;
import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.*;
import com.retailsvc.http.spec.schema.*;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RequestPreparationFilterTest {
  private final HashMap<String, Object> attrs = new HashMap<>();

  private HttpExchange exchange(String method, String path, byte[] body) {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getRequestMethod()).thenReturn(method);
    Mockito.when(ex.getRequestURI()).thenReturn(URI.create(path));
    Mockito.when(ex.getRequestHeaders()).thenReturn(new Headers());
    Mockito.when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
    Mockito.doAnswer(inv -> attrs.put(inv.getArgument(0), inv.getArgument(1)))
        .when(ex).setAttribute(Mockito.anyString(), Mockito.any());
    Mockito.when(ex.getAttribute(Mockito.anyString()))
        .thenAnswer(inv -> attrs.get((String) inv.getArgument(0)));
    return ex;
  }

  private Spec specWith(Operation... ops) {
    return new Spec("3.1.0", new Info("t","1"), List.of(new Server("/")),
        List.of(ops), Map.of(), Map.of());
  }

  @Test
  void successPathSetsAttributes() throws Exception {
    var op = new Operation("get-user", HttpMethod.GET, PathTemplate.compile("/users/{id}"),
        Optional.empty(), List.of(), Map.of());
    Spec spec = specWith(op);
    JsonMapper m = body -> new String(body);
    Filter f = new RequestPreparationFilter(spec, new Router(spec.operations()),
        new DefaultValidator(spec::resolveSchema), m);

    HttpExchange ex = exchange("GET", "/users/42", new byte[0]);
    Filter.Chain chain = Mockito.mock(Filter.Chain.class);

    f.doFilter(ex, chain);

    assertThat(Request.operationId(ex)).isEqualTo("get-user");
    assertThat(Request.pathParams(ex)).containsEntry("id", "42");
    Mockito.verify(chain).doFilter(ex);
  }

  @Test
  void unknownPathThrowsNotFound() {
    Spec spec = specWith(new Operation("a", HttpMethod.GET, PathTemplate.compile("/x"),
        Optional.empty(), List.of(), Map.of()));
    JsonMapper m = body -> new String(body);
    Filter f = new RequestPreparationFilter(spec, new Router(spec.operations()),
        new DefaultValidator(spec::resolveSchema), m);

    HttpExchange ex = exchange("GET", "/missing", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, Mockito.mock(Filter.Chain.class)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void wrongMethodThrowsMethodNotAllowed() {
    Spec spec = specWith(new Operation("a", HttpMethod.GET, PathTemplate.compile("/x"),
        Optional.empty(), List.of(), Map.of()));
    JsonMapper m = body -> new String(body);
    Filter f = new RequestPreparationFilter(spec, new Router(spec.operations()),
        new DefaultValidator(spec::resolveSchema), m);

    HttpExchange ex = exchange("POST", "/x", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, Mockito.mock(Filter.Chain.class)))
        .isInstanceOf(MethodNotAllowedException.class);
  }

  @Test
  void invalidQueryParamThrowsValidation() {
    var stringSchema = new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null);
    var op = new Operation("a", HttpMethod.GET, PathTemplate.compile("/x"),
        Optional.empty(),
        List.of(new Parameter("q", Parameter.Location.QUERY, true, stringSchema)),
        Map.of());
    Spec spec = specWith(op);
    JsonMapper m = body -> new String(body);
    Filter f = new RequestPreparationFilter(spec, new Router(spec.operations()),
        new DefaultValidator(spec::resolveSchema), m);

    HttpExchange ex = exchange("GET", "/x?q=ab", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, Mockito.mock(Filter.Chain.class)))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().pointer())
        .isEqualTo("/query/q");
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

```java
package com.retailsvc.http.internal;

import static com.retailsvc.http.Request.*;

import com.retailsvc.http.JsonMapper;
import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.MediaType;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.Parameter;
import com.retailsvc.http.spec.RequestBody;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.Validator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;

public final class RequestPreparationFilter extends Filter {

  private final Spec spec;
  private final Router router;
  private final Validator validator;
  private final JsonMapper jsonMapper;

  public RequestPreparationFilter(Spec spec, Router router, Validator validator, JsonMapper jsonMapper) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String description() { return "Request preparation"; }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    exchange.setAttribute(BODY, body);

    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    String path = stripBasePath(exchange.getRequestURI().getPath());

    var matchOpt = router.match(method, path);
    if (matchOpt.isEmpty()) {
      var allowed = router.allowedMethods(path);
      if (allowed.isEmpty()) throw new NotFoundException(method + " " + path);
      throw new MethodNotAllowedException(allowed);
    }
    Router.Match match = matchOpt.get();

    Operation op = match.operation();
    exchange.setAttribute(OPERATION_ID, op.operationId());
    exchange.setAttribute(PATH_PARAMETERS, match.pathParameters());

    validateParameters(exchange, op, match.pathParameters());
    validateBody(exchange, op, body);

    chain.doFilter(exchange);
  }

  private String stripBasePath(String path) {
    String base = spec.basePath();
    if (base == null || base.isEmpty()) return path;
    return path.startsWith(base) ? path.substring(base.length()) : path;
  }

  private void validateParameters(HttpExchange exchange, Operation op, Map<String, String> pathParams) {
    Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
    for (Parameter p : op.parameters()) {
      String pointer = "/" + p.in().name().toLowerCase(Locale.ROOT) + "/" + p.name();
      String value = switch (p.in()) {
        case PATH   -> pathParams.get(p.name());
        case QUERY  -> query.get(p.name());
        case HEADER -> exchange.getRequestHeaders().getFirst(p.name());
        case COOKIE -> null; // handled by future spec
      };
      if (value == null) {
        if (p.required()) {
          throw new com.retailsvc.http.ValidationException(
              new com.retailsvc.http.validate.ValidationError(pointer, "required",
                  "required " + p.in().name().toLowerCase(Locale.ROOT) + " parameter is missing", null));
        }
        continue;
      }
      validator.validate(value, p.schema(), pointer);
    }
  }

  private void validateBody(HttpExchange exchange, Operation op, byte[] body) {
    Optional<RequestBody> rb = op.requestBody();
    if (rb.isEmpty()) return;
    if (body.length == 0) {
      if (rb.get().required()) {
        throw new com.retailsvc.http.ValidationException(
            new com.retailsvc.http.validate.ValidationError("/body", "required",
                "request body is required", null));
      }
      return;
    }
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null) contentType = "application/json";
    contentType = contentType.split(";", 2)[0].trim();
    MediaType mt = rb.get().content().get(contentType);
    if (mt == null) {
      throw new com.retailsvc.http.ValidationException(
          new com.retailsvc.http.validate.ValidationError("/body", "content-type",
              "unsupported content type: " + contentType, null));
    }
    Object parsed = jsonMapper.mapFrom(body);
    exchange.setAttribute(PARSED_BODY, parsed);
    validator.validate(parsed, mt.schema(), "");
  }

  private static Map<String, String> parseQuery(String query) {
    if (query == null || query.isBlank()) return Map.of();
    Map<String, String> out = new HashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      out.putIfAbsent(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return out;
  }
}
```

- [ ] **Step 4: Verify** — 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java \
        src/test/java/com/retailsvc/http/internal/RequestPreparationFilterTest.java
git commit -m "feat(internal): RequestPreparationFilter combines body capture, routing, validation"
```

---

### Task H3: DispatchHandler

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`
- Test: `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`
- (Old `MissingOperationHandlerException` lives in `com.retailsvc.http.openapi.exceptions`. Move to public package now and adjust import; old file deleted in Phase K.)
- Modify: `src/main/java/com/retailsvc/http/MissingOperationHandlerException.java` (new — same name as old, public package)

- [ ] **Step 1: Test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DispatchHandlerTest {
  private final Map<String, Object> attrs = new HashMap<>();

  private HttpExchange exchange(String operationId) {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getAttribute(Request.OPERATION_ID)).thenReturn(operationId);
    return ex;
  }

  @Test
  void invokesRegisteredHandler() throws Exception {
    HttpHandler handler = Mockito.mock(HttpHandler.class);
    new DispatchHandler(Map.of("get-x", handler)).handle(exchange("get-x"));
    Mockito.verify(handler).handle(Mockito.any());
  }

  @Test
  void throwsWhenHandlerMissing() {
    DispatchHandler d = new DispatchHandler(Map.of());
    assertThatThrownBy(() -> d.handle(exchange("ghost")))
        .isInstanceOf(MissingOperationHandlerException.class);
  }
}
```

- [ ] **Step 2: Run — fails**

- [ ] **Step 3: Implement**

`MissingOperationHandlerException.java`:

```java
package com.retailsvc.http;

public final class MissingOperationHandlerException extends RuntimeException {
  public MissingOperationHandlerException(String operationId) {
    super("no handler registered for operationId=" + operationId);
  }
}
```

`DispatchHandler.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {
  private final Map<String, HttpHandler> handlers;

  public DispatchHandler(Map<String, HttpHandler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String opId = Request.operationId(exchange);
    HttpHandler h = handlers.get(opId);
    if (h == null) throw new MissingOperationHandlerException(opId);
    h.handle(exchange);
  }
}
```

- [ ] **Step 4: Verify** — 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/MissingOperationHandlerException.java \
        src/main/java/com/retailsvc/http/internal/DispatchHandler.java \
        src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java
git commit -m "feat(internal): DispatchHandler dispatches to registered HttpHandler by operationId"
```

---

## Phase I — Wire it all into OpenApiServer

### Task I1: Rewrite OpenApiServer to use new types

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`
- Test: `src/test/java/com/retailsvc/http/OpenApiServerTest.java` (existing — will be rewritten)

The existing `OpenApiServer` references `com.retailsvc.http.openapi.model.OpenApi` and `com.retailsvc.http.openapi.model.JsonMapper`. We rewrite the file in place to use the new types.

- [ ] **Step 1: Read existing tests to see what's being asserted**

Run: `grep -l "new OpenApiServer" src/test`

Existing tests construct `OpenApiServer` with the old types. They will be migrated in Task I2; for this task we only need the production class to compile and be correct.

- [ ] **Step 2: Rewrite the class**

Replace the contents of `src/main/java/com/retailsvc/http/OpenApiServer.java`:

```java
package com.retailsvc.http;

import static java.lang.Thread.ofVirtual;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.internal.ExceptionFilter;
import com.retailsvc.http.internal.RequestPreparationFilter;
import com.retailsvc.http.internal.Router;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenApiServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApiServer.class);
  private static final int DEFAULT_PORT = 8080;

  private final HttpServer httpServer;

  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler) throws IOException {
    this(spec, jsonMapper, handlers, exceptionHandler, DEFAULT_PORT);
  }

  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler,
      int port) throws IOException {

    requireNonNull(spec, "Spec must not be null");
    requireNonNull(jsonMapper, "JsonMapper must not be null");
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

    HttpContext ctx = httpServer.createContext(
        Optional.ofNullable(spec.basePath()).orElse("/"));
    ctx.getFilters().add(new ExceptionFilter(exceptionHandler));
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, jsonMapper));
    ctx.setHandler(new DispatchHandler(handlers));

    httpServer.createContext("/", Handlers.notFoundHandler());
    httpServer.start();

    LOG.info("Server started (port {}) in {}ms", port, System.currentTimeMillis() - t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  @Override
  public void close() {
    if (httpServer != null) httpServer.stop(0);
  }
}
```

- [ ] **Step 3: Existing tests will fail to compile** — that's expected. Don't `mvn test` here yet; proceed to I2.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java
git commit -m "refactor(http): rewrite OpenApiServer against new Spec/Validator/Router types"
```

---

### Task I2: Migrate `OpenApiServerTest`, `OpenApiServerIT`, and the example launcher

**Files:**
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerTest.java`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java`
- Modify: `src/test/java/com/retailsvc/http/ServerBaseTest.java`
- Modify: `src/test/java/com/retailsvc/http/start/ServerLauncher.java`
- Modify: `src/test/java/com/retailsvc/http/start/EchoHandler.java` and any handler that implements `GetRequestBody`

This task covers wiring all existing test code to the new API. The migration recipe is identical for each file.

- [ ] **Step 1: Migrate `ServerLauncher.java`** (the example)

Old code calls `parseSpecification("openapi.json", s -> gson.fromJson(s, OpenApi.class))` etc. Replace with:

```java
package com.retailsvc.http.start;

import com.google.gson.Gson;
import com.retailsvc.http.Handlers;
import com.retailsvc.http.JsonMapper;
import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ServerLauncher {
  public static void main(String[] args) throws IOException {
    Gson gson = new Gson();

    String text;
    try (InputStream in = ServerLauncher.class.getResourceAsStream("/openapi.json")) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
    Spec spec = Spec.from(raw);

    JsonMapper mapper = body -> gson.fromJson(new String(body), Object.class);

    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());
    handlers.put("post-list", new PostListObjectsHandler());
    handlers.put("echo", new EchoHandler());
    handlers.put("param", new ParamHandler());

    new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());
  }
}
```

- [ ] **Step 2: Migrate `*Handler` test classes**

Any handler that uses `getRequestBody(exchange)` from `GetRequestBody` should call `Request.bytes(exchange)` or `Request.parsed(exchange)` instead. Remove `implements GetRequestBody`.

Example for `EchoHandler`:

```java
import com.retailsvc.http.Request;
// remove: import com.retailsvc.http.openapi.model.GetRequestBody;

public class EchoHandler implements HttpHandler {  // remove GetRequestBody
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] body = Request.bytes(exchange);
    // unchanged: write body back
    ...
  }
}
```

- [ ] **Step 3: Migrate `ServerBaseTest.java`**

Replace its `OpenApiServer` setup helper to construct via the new API exactly as in `ServerLauncher`. All subclasses (`OpenApiServerTest`, `OpenApiServerIT`) inherit.

- [ ] **Step 4: Migrate test assertions for the new error format**

The existing `OpenApiServerIT` likely asserts `400` with empty body for invalid input. Update to assert:
- Status 400
- `Content-Type: application/problem+json`
- Body contains `"keyword":"..."` and `"pointer":"..."`

Where the test asserts `404`/`500` for unknown operation, change to assert `404` (now from `NotFoundException`).

- [ ] **Step 5: Run full suite**

Run: `mvn -q test`
Expected: all green. If a test depends on `BodyHandler.RequestBodyWrapper` directly, swap to `Request.bytes(exchange)`.

If anything fails because `OpenApiValidationFilter` test assertions don't apply anymore, those tests get deleted in Phase K — for now, mark them `@Disabled("removed in Phase K")` to keep the suite green.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/retailsvc/http/
git commit -m "refactor(test): migrate test launcher, handlers, and integration tests to new API"
```

---

### Task I3: Verify integration test against the existing fixture

**Files:**
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java` (already touched in I2; this task validates the spec fixture round-trips)

- [ ] **Step 1: Run integration tests**

Run: `mvn -q verify`
Expected: BUILD SUCCESS, all surefire + failsafe tests green.

If the fixture exercises features not yet implemented (path matching, query params, body parsing) ensure they all pass with the new validator. If any specific case fails because the new validator is stricter than the old (e.g., an invalid spec that the old code accepted), fix the fixture and document it in the commit message.

- [ ] **Step 2: Commit any fixture adjustments**

```bash
git add src/test/resources/
git commit -m "test: align fixture with stricter new validator"
```

(Skip if no changes were needed.)

---

## Phase J — Documentation

### Task J1: Update README.md

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update Prerequisites**

Replace "Java SDK 21 or later" with "Java SDK 25 or later".

- [ ] **Step 2: Replace the Basic Usage code blocks**

Replace the code that shows `parseSpecification(...)` and the verbose `JsonMapper` anonymous class with the new pattern:

````markdown
``` java
public class YourServerLauncher {
  public static void main(String[] args) throws Exception {
    Gson gson = new Gson();

    // Parse spec to a generic Map (works for JSON; for YAML use SnakeYAML).
    String text = Files.readString(Path.of("openapi.json"));
    Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
    Spec spec = Spec.from(raw);

    // Body parser. Returns a Map for objects, List for arrays.
    JsonMapper mapper = body -> gson.fromJson(new String(body), Object.class);

    // Handlers by operationId.
    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());

    new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());
  }
}
```
````

- [ ] **Step 3: Update Handler example**

Replace `implements HttpHandler, GetRequestBody` with `implements HttpHandler` and use `Request.bytes(exchange)` / `Request.parsed(exchange)` to access body data.

- [ ] **Step 4: Add a "YAML" subsection**

```markdown
### YAML specifications
For YAML, replace the JSON parsing line with SnakeYAML:
```java
Map<String, Object> raw = new Yaml().load(Files.newInputStream(Path.of("openapi.yaml")));
```
The rest is identical.
```

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: update README for Java 25 and post-refactor public API"
```

---

### Task J2: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Replace "Java 21" with "Java 25" in the Project paragraph**

- [ ] **Step 2: Replace the Architecture section**

Replace the description of the request flow with the new pipeline (`ExceptionFilter` → `RequestPreparationFilter` → `DispatchHandler`). Replace the "Key abstractions" bullets with: sealed `Schema`, `Spec.from(Map<String,Object>)`, `Request` static helper, `DefaultValidator` with pattern-match dispatch, `Router` with exact + templated indexes.

- [ ] **Step 3: Verify no stale references**

Run: `grep -n "Java 21\|java-21\|release>21<\|version 21\|BodyHandler\|OpenApiValidationFilter\|GetRequestBody\|SpecificationLoader\|RequestDispatchingHandler\|ExceptionHandlingFilter\|com.retailsvc.http.openapi" CLAUDE.md README.md`
Expected: no output (or only legitimate historical references — fix them).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): refresh architecture section for refactor"
```

---

## Phase K — Delete old code

### Task K1: Delete the old packages

**Files:**
- Delete: entire `src/main/java/com/retailsvc/http/openapi/` tree
- Delete: `src/main/java/com/retailsvc/http/BodyHandler.java`
- Delete: `src/main/java/com/retailsvc/http/ExceptionHandlingFilter.java`
- Delete: any test files under `src/test/java/com/retailsvc/http/openapi/` that test the deleted code

- [ ] **Step 1: Delete files**

```bash
git rm -r src/main/java/com/retailsvc/http/openapi/
git rm src/main/java/com/retailsvc/http/BodyHandler.java
git rm src/main/java/com/retailsvc/http/ExceptionHandlingFilter.java
git rm -r src/test/java/com/retailsvc/http/openapi/
```

- [ ] **Step 2: Find leftover references**

```bash
grep -rn "com.retailsvc.http.openapi\|BodyHandler\|ExceptionHandlingFilter\|GetRequestBody\|RequestDispatchingHandler\|OpenApiValidationFilter\|SpecificationLoader" src/
```
Expected: no matches. Fix any that remain.

- [ ] **Step 3: Run full suite**

Run: `mvn -q verify`
Expected: BUILD SUCCESS, all green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete legacy openapi.* packages and old filter/wrapper classes"
```

---

## Phase L — Verification sweep

### Task L1: Final grep + coverage check

**Files:**
- (read-only verification)

- [ ] **Step 1: Java version sweep**

```bash
grep -rn "Java 21\|java-21\|release>21<" --include="*.md" --include="*.java" --include="*.xml" --include="*.yaml" --include="*.yml" --include="Dockerfile" .
```
Expected: no results (or only inside the spec doc historical context, which is fine).

- [ ] **Step 2: Old API symbol sweep**

```bash
grep -rn "OpenApi\.parse\|JsonMapper.*<.*>\|getRequestBody(exchange)\|BodyHandler\b\|operation-id" src/main/java
```
Expected: no results in `src/main/java`.

- [ ] **Step 3: Coverage**

Run: `mvn -q verify`
Open `target/site/jacoco/index.html` and confirm `com.retailsvc.http.validate`, `com.retailsvc.http.spec`, `com.retailsvc.http.spec.schema`, and `com.retailsvc.http.internal` are at or above 80% line coverage.

- [ ] **Step 4: Hand-test the example**

Run: `mvn test-compile exec:java -Dexec.mainClass=com.retailsvc.http.start.ServerLauncher -Dexec.classpathScope=test`

In a separate terminal:
```bash
curl -i http://localhost:8080/api/get-data
curl -i -X POST http://localhost:8080/api/post-data -H 'content-type: application/json' -d '{"id":"x"}'
curl -i http://localhost:8080/api/missing  # expect 404
curl -i -X POST http://localhost:8080/api/get-data  # expect 405 Allow: GET
curl -i -X POST http://localhost:8080/api/post-data -H 'content-type: application/json' -d '{}'  # expect 400 problem+json
```

Stop the server with Ctrl-C.

- [ ] **Step 5: Push the branch (if user requests)**

```bash
git push -u origin refactor/openapi-3.1-readiness
```

(Do not push without confirmation — this is the user's call.)

---

## Self-review checklist for the engineer

Before declaring the refactor done, walk through these:

- [ ] All 122 original tests have been migrated or deleted, with corresponding new coverage
- [ ] `mvn -q verify` is green
- [ ] No file under `src/main/java/com/retailsvc/http/openapi/` exists
- [ ] `BodyHandler`, `ExceptionHandlingFilter`, `GetRequestBody`, `RequestDispatchingHandler`, `OpenApiValidationFilter`, `SpecificationLoader`, `OpenApi`, `Components`, `OpenApiConstants`, `PathItem`, the per-kind validator classes, and the seven old exception classes from `openapi.exceptions` are all gone
- [ ] `Schema.minimum` / `maximum` defaulting to `Double.MIN_VALUE` / `Double.MAX_VALUE` (the bug) is gone — new model uses `null` for "unspecified"
- [ ] Combinator records exist; `DefaultValidator` throws `UnsupportedOperationException` on them; parser produces them
- [ ] `Spec.from(Map<String,Object>)` is the single entry point; no `Function<String, OpenApi>` or `Function<Object, String> toJson` callbacks remain
- [ ] `JsonMapper` is `@FunctionalInterface` with `Object mapFrom(byte[])`
- [ ] Default 400 response is `application/problem+json`
- [ ] `Dockerfile`, `.java-version`, `pom.xml`, `README.md`, `CLAUDE.md` all reference Java 25
- [ ] None of the in-scope "free" 3.1 keywords are missing: `minLength`, `maxLength`, `minItems`, `maxItems`, `uniqueItems`, `multipleOf`, `exclusiveMinimum`, `exclusiveMaximum`, `type:["string","null"]`
