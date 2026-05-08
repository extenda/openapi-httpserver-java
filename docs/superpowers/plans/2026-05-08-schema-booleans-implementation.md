# Schema Booleans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support JSON Schema 2020-12 boolean schemas (`true` / `false` as a bare schema) in OpenAPI 3.1.

**Architecture:** Two new sealed-hierarchy records (`AlwaysSchema`, `NeverSchema`) join `Schema`. The parser entry signature changes from `parse(Map<String, Object>)` to `parse(Object)`; a single dispatch at the top recognizes `Boolean` and produces the new records. Recursive callers and external callers in `Spec.java` drop their `Map` casts. The validator gains two new cases: a no-op pass for `AlwaysSchema` and an unconditional `fail` for `NeverSchema`.

**Tech Stack:** Java 25, JUnit 5 + AssertJ + Mockito, Maven Surefire/Failsafe, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-08-schema-booleans-design.md`

**Branch:** `feat/schema-booleans` (already checked out).

---

## Task 1: Schema records, parser change, parser tests

**Files:**
- Create: `src/main/java/com/retailsvc/http/spec/schema/AlwaysSchema.java`
- Create: `src/main/java/com/retailsvc/http/spec/schema/NeverSchema.java`
- Modify: `src/main/java/com/retailsvc/http/spec/schema/Schema.java` (extend `permits`)
- Modify: `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java` (entry signature, internal recursive calls, helper rename)
- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java` (drop `Map` casts on the four `SchemaParser.parse(...)` callers)
- Modify: `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`

### Step 1.1: Add the schema records and extend `permits`

- [x] **Step 1: Create `AlwaysSchema.java`**

Write this file at `src/main/java/com/retailsvc/http/spec/schema/AlwaysSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record AlwaysSchema() implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
```

- [x] **Step 2: Create `NeverSchema.java`**

Write this file at `src/main/java/com/retailsvc/http/spec/schema/NeverSchema.java`:

```java
package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NeverSchema() implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
```

- [x] **Step 3: Extend `Schema.java` permits clause**

Open `src/main/java/com/retailsvc/http/spec/schema/Schema.java`. Add `AlwaysSchema` and `NeverSchema` to the `permits` list. The full list becomes (alphabetized as in the existing file):

```java
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
}
```

### Step 1.2: Add failing parser tests

- [x] **Step 4: Append parser tests**

Append to `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java` (inside the class, before the final `}`):

```java
@Test
void parsesTrueAsAlwaysSchema() {
  assertThat(SchemaParser.parse(Boolean.TRUE)).isInstanceOf(AlwaysSchema.class);
}

@Test
void parsesFalseAsNeverSchema() {
  assertThat(SchemaParser.parse(Boolean.FALSE)).isInstanceOf(NeverSchema.class);
}

@Test
void rejectsNonMapNonBooleanRawSchema() {
  org.assertj.core.api.Assertions.assertThatThrownBy(() -> SchemaParser.parse("oops"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("schema must be a boolean or an object");
}

@Test
void parsesObjectWithBooleanPropertySchemas() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "object",
              "properties", Map.of("x", Boolean.TRUE, "y", Boolean.FALSE)));
  assertThat(s).isInstanceOf(ObjectSchema.class);
  ObjectSchema obj = (ObjectSchema) s;
  assertThat(obj.properties().get("x")).isInstanceOf(AlwaysSchema.class);
  assertThat(obj.properties().get("y")).isInstanceOf(NeverSchema.class);
}

@Test
void parsesArrayWithBooleanItemsSchema() {
  Schema s = SchemaParser.parse(Map.of("type", "array", "items", Boolean.TRUE));
  assertThat(s).isInstanceOf(ArraySchema.class);
  assertThat(((ArraySchema) s).items()).isInstanceOf(AlwaysSchema.class);
}
```

The `assertThatThrownBy` static is not yet imported — leave the fully-qualified call in place; it lives only in this single test and matches the pattern used elsewhere in the codebase before that import was added in `feat/combinators`.

- [x] **Step 5: Run the failing tests**

Run: `mvn -q test -Dtest=SchemaParserTest`

Expected: the build fails to compile. The new tests call `SchemaParser.parse(Boolean.TRUE)` / `parse("oops")`, but the existing `parse(Map<String, Object>)` signature does not accept those argument types. This compile-error is the red state for this TDD step — the next step (signature change) makes it green.

`SchemaParserTest` lives in package `com.retailsvc.http.spec.schema`, the same package as `AlwaysSchema` and `NeverSchema`. No imports are needed for the new record names.

### Step 1.3: Change the parser entry to accept `Object`

- [x] **Step 6: Rewrite `SchemaParser.parse(...)` entry**

Open `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`. Replace the public `parse(Map<String, Object>)` method with a new `parse(Object)` entry, and rename the original body to a private `parseMap(...)`. Concretely:

1. Change the method signature on line 16 from:

```java
public static Schema parse(Map<String, Object> raw) {
```

to a new public method and a private helper:

```java
public static Schema parse(Object raw) {
  if (raw instanceof Boolean b) {
    return b ? new AlwaysSchema() : new NeverSchema();
  }
  if (raw instanceof Map<?, ?> map) {
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) map;
    return parseMap(typed);
  }
  throw new IllegalArgumentException("schema must be a boolean or an object, was: " + raw);
}

@SuppressWarnings("unchecked")
private static Schema parseMap(Map<String, Object> raw) {
  // existing body, unchanged in behaviour
  if (raw.containsKey("$ref")) {
    return new RefSchema((String) raw.get("$ref"));
  }
  if (raw.containsKey("oneOf")) {
    return new OneOfSchema(parseList(raw, "oneOf"));
  }
  if (raw.containsKey("anyOf")) {
    return new AnyOfSchema(parseList(raw, "anyOf"));
  }
  if (raw.containsKey("allOf")) {
    return new AllOfSchema(parseList(raw, "allOf"));
  }
  if (raw.containsKey("not")) {
    return new NotSchema(parse(raw.get("not")));
  }
  if (raw.containsKey("const")) {
    return new ConstSchema(raw.get("const"));
  }
  if (raw.containsKey("enum") && !raw.containsKey("type")) {
    return new EnumSchema(List.copyOf((List<Object>) raw.get("enum")));
  }

  Set<TypeName> types = parseTypes(raw);

  TypeName primary =
      types.stream().filter(t -> t != TypeName.NULL).findFirst().orElse(TypeName.NULL);

  return switch (primary) {
    case STRING -> parseString(raw, types);
    case INTEGER -> parseInteger(raw, types);
    case NUMBER -> parseNumber(raw, types);
    case BOOLEAN -> new BooleanSchema(types);
    case NULL -> new NullSchema();
    case OBJECT -> parseObject(raw, types);
    case ARRAY -> parseArray(raw, types);
  };
}
```

The body of `parseMap` is the existing body of the old `parse` method, with one tiny inline change: line 31 (`new NotSchema(parse((Map<String, Object>) raw.get("not")))`) becomes `new NotSchema(parse(raw.get("not")))` — drop the cast so a `not: true` schema passes through the new entry.

2. Drop the `Map` casts on the recursive calls inside the helpers:

  - Line ~111 (inside `parseObject`): change `parse((Map<String, Object>) e.getValue())` to `parse(e.getValue())`.
  - Line ~130 (inside `parseAdditionalProperties`): leave as-is. `parseAdditionalProperties` already handles `null` / `Boolean` before reaching `parse`, so the remaining `parse((Map<String, Object>) value)` is unreachable for booleans. You may drop the cast for symmetry, but it's not required.
  - Line ~137 (inside `parseArray`): no change. `items` is already typed `Map<String, Object>` from the cast above it, so the call site does not change. The empty-`items` short-circuit (`items.isEmpty() ? new NullSchema() : parse(items)`) stays — that's a pre-existing quirk, out of scope.
  - Line ~151 (inside `parseList`): change `parseList`'s parameter type from `List<Map<String, Object>>` to `List<Object>` so boolean branches inside `oneOf` / `anyOf` / `allOf` survive. Concrete edit:

```java
@SuppressWarnings("unchecked")
private static List<Schema> parseList(Map<String, Object> raw, String key) {
  List<Object> raws = (List<Object>) raw.get(key);
  List<Schema> out = new ArrayList<>(raws.size());
  for (Object r : raws) {
    out.add(parse(r));
  }
  return List.copyOf(out);
}
```

### Step 1.4: Drop `Map` casts on external `parse` callers in `Spec.java`

- [x] **Step 7: Edit `Spec.java`**

Open `src/main/java/com/retailsvc/http/spec/Spec.java`. Drop the `(Map<String, Object>)` cast on the four direct `SchemaParser.parse(...)` callers. The lines (in current commit):

- Line 107: `out.put(e.getKey(), SchemaParser.parse((Map<String, Object>) e.getValue()));`
- Line 130–131: inside `parseParameter`, `SchemaParser.parse((Map<String, Object>) raw.getOrDefault(SCHEMA_KEY, Map.of("type", "string")))`
- Line 197–198: inside `parseRequestBody`, `SchemaParser.parse((Map<String, Object>) mt.getOrDefault(SCHEMA_KEY, Map.of("type", "object")))`
- Line 215: inside `parseResponses`, `new MediaType(SchemaParser.parse((Map<String, Object>) mt.get(SCHEMA_KEY)))`

After the edit, each call passes the raw `Object` directly:

- Line 107: `out.put(e.getKey(), SchemaParser.parse(e.getValue()));`
- Line 130–131: `SchemaParser.parse(raw.getOrDefault(SCHEMA_KEY, Map.of("type", "string")))`
- Line 197–198: `SchemaParser.parse(mt.getOrDefault(SCHEMA_KEY, Map.of("type", "object")))`
- Line 215: `new MediaType(SchemaParser.parse(mt.get(SCHEMA_KEY)))`

The four `@SuppressWarnings("unchecked")` annotations on the surrounding methods stay — other casts in those methods remain.

### Step 1.5: Verify and commit

- [x] **Step 8: Run the parser tests**

Run: `mvn -q test -Dtest=SchemaParserTest`

Expected: all parser tests pass (existing + the 5 new).

- [x] **Step 9: Run the full unit suite**

Run: `mvn -q test`

Expected: BUILD SUCCESS. No regressions in `Spec`-driven tests, validator tests, or container tests.

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/AlwaysSchema.java \
        src/main/java/com/retailsvc/http/spec/schema/NeverSchema.java \
        src/main/java/com/retailsvc/http/spec/schema/Schema.java \
        src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java \
        src/main/java/com/retailsvc/http/spec/Spec.java \
        src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java
git commit -m "$(cat <<'EOF'
feat: Parse boolean schemas as AlwaysSchema and NeverSchema

JSON Schema 2020-12 allows a bare true or false where a schema is
expected: true accepts any value, false rejects every value. Add the
two new sealed-hierarchy records and change SchemaParser.parse to
accept Object so boolean values dispatch to the new records. Recursive
callers (parseObject, parseArray, parseList, NotSchema) and external
callers in Spec.java drop their Map casts. AdditionalProperties keeps
its existing Boolean handling.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit hook (Google Java Formatter, commitlint, editorconfig) may reformat — re-stage and re-run if it does.

---

## Task 2: Validator branches + tests

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`
- Modify: `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java`

### Step 2.1: Add failing validator tests

- [x] **Step 1: Append validator tests**

Append to `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java` (inside the class, before the final `}`):

```java
@Test
void alwaysSchemaAcceptsString() {
  v.validate("anything", new AlwaysSchema(), "/v");
}

@Test
void alwaysSchemaAcceptsInteger() {
  v.validate(42, new AlwaysSchema(), "/v");
}

@Test
void alwaysSchemaAcceptsObject() {
  v.validate(Map.of("a", 1), new AlwaysSchema(), "/v");
}

@Test
void alwaysSchemaAcceptsNull() {
  v.validate(null, new AlwaysSchema(), "/v");
}

@Test
void neverSchemaRejectsString() {
  assertThatThrownBy(() -> v.validate("anything", new NeverSchema(), "/v"))
      .isInstanceOf(ValidationException.class)
      .satisfies(
          t -> {
            var err = ((ValidationException) t).error();
            assertThat(err.keyword()).isEqualTo("false");
            assertThat(err.message()).contains("rejects all values");
          });
}

@Test
void neverSchemaRejectsInteger() {
  assertThatThrownBy(() -> v.validate(42, new NeverSchema(), "/v"))
      .isInstanceOf(ValidationException.class)
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("false");
}

@Test
void neverSchemaRejectsNull() {
  assertThatThrownBy(() -> v.validate(null, new NeverSchema(), "/v"))
      .isInstanceOf(ValidationException.class)
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("false");
}
```

Add the necessary imports near the existing schema imports at the top of the file:

```java
import com.retailsvc.http.spec.schema.AlwaysSchema;
import com.retailsvc.http.spec.schema.NeverSchema;
```

If `Map` isn't already imported (it likely is for surrounding tests), add `import java.util.Map;` next to other `java.util.*` imports.

- [x] **Step 2: Run the failing tests**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest`

Expected: 7 new tests fail. The validator currently has no case for `AlwaysSchema` or `NeverSchema`, so the sealed `switch` is non-exhaustive and the file no longer compiles. (Recall that Java's exhaustive switch on a sealed type requires all permitted subtypes to be covered.) Compilation will fail with "switch expression does not cover all possible input values" — that's the expected red state for TDD here.

### Step 2.2: Add the validator branches

- [x] **Step 3: Edit `DefaultValidator.validate(...)`**

Open `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`. Inside the `switch (schema)` block in `validate(...)`, add two new cases. Place them adjacent to the other "trivial" cases (after `BooleanSchema`/`NullSchema`, before the primitive cases — the exact ordering doesn't matter, but stay consistent with the file's existing case order):

```java
case AlwaysSchema _ -> { /* accepts any value, including null */ }
case NeverSchema _ -> fail(pointer, "false", "schema rejects all values", value);
```

Add the imports near the other schema imports at the top:

```java
import com.retailsvc.http.spec.schema.AlwaysSchema;
import com.retailsvc.http.spec.schema.NeverSchema;
```

- [x] **Step 4: Run the validator tests**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest`

Expected: BUILD SUCCESS. All existing tests + the 7 new ones pass.

- [x] **Step 5: Run the full unit suite**

Run: `mvn -q test`

Expected: BUILD SUCCESS, no regressions.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java
git commit -m "$(cat <<'EOF'
feat: Validate AlwaysSchema and NeverSchema in DefaultValidator

AlwaysSchema is a no-op pass for every value, including null.
NeverSchema fails unconditionally with keyword "false" and message
"schema rejects all values". Adds 7 unit tests covering both records
across string / integer / object / null inputs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit hook may reformat — re-stage / re-run as needed.

---

## Task 3: Integration test — `/gates` endpoint

**Files:**
- Modify: `src/test/resources/openapi.yaml`
- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java`

### Step 3.1: Extend the OpenAPI fixture

- [x] **Step 1: Add `/gates` to `openapi.yaml`**

Open `src/test/resources/openapi.yaml`. After the existing `/blocked` (or `/shapes` if `/blocked` doesn't exist on this branch — see note below) endpoint, add:

```yaml
  /gates:
    post:
      operationId: post-gate
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [open]
              properties:
                open: true
                blocked: false
      responses:
        "200":
          description: OK
```

**Note about base branch.** This branch (`feat/schema-booleans`) is cut from `master`, which does NOT have `/shapes`, `/filters`, or `/blocked` (those live on `feat/combinators`). The fixture on this branch instead has stub `/anyOf` and `/allOf` paths with `post: {}`. Add `/gates` after `/anyOf` and `/allOf` to keep the surrounding ordering predictable. Do NOT remove the `/anyOf` / `/allOf` stubs — that cleanup is owned by `feat/combinators`.

- [x] **Step 2: Mirror in `openapi.json`**

Open `src/test/resources/openapi.json`. Find the `"/anyOf"` and `"/allOf"` keys. Add `"/gates"` immediately after them. The block:

```json
    "/gates": {
      "post": {
        "operationId": "post-gate",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "required": ["open"],
                "properties": {
                  "open": true,
                  "blocked": false
                }
              }
            }
          }
        },
        "responses": { "200": { "description": "OK" } }
      }
    }
```

Mind the trailing commas: the previous path entry needs a trailing comma if it doesn't already have one, and the new `/gates` block follows the existing `paths` map style.

### Step 3.2: Add the integration tests

- [x] **Step 3: Add `Gates` nested class to `OpenApiServerIT.java`**

Open `src/test/java/com/retailsvc/http/OpenApiServerIT.java`. Add a new nested class at the bottom of the outer class (immediately before its final `}`). All test method names are pure camelCase per the project convention:

```java
@Nested
class Gates {

  String path = "/gates";

  @Test
  void postGateBodyWithOnlyOpenReturns200() {
    try (var server = newServer(Map.of("post-gate", new EchoHandler()));
        var client = httpClient()) {
      var body = "{\"open\":\"anything\"}";
      var request = newRequest(server, path, "POST", ofString(body));

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
    } catch (IOException e) {
      fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e);
    }
  }

  @Test
  void postGateBodyWithBlockedReturns400() {
    try (var server = newServer(Map.of("post-gate", new EchoHandler()));
        var client = httpClient()) {
      // Even null in 'blocked' triggers the false-schema rejection,
      // because NeverSchema rejects every value.
      var body = "{\"open\":\"x\",\"blocked\":\"anything\"}";
      var request = newRequest(server, path, "POST", ofString(body));

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .contains("application/problem+json");
      assertThat(response.body()).contains("false");
    } catch (IOException e) {
      fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e);
    }
  }
}
```

`ofString`, `BodyHandlers`, `assertThat`, `fail`, and `EchoHandler` are already imported in `OpenApiServerIT.java`. `org.junit.jupiter.api.Nested` and `IOException` likewise. No new imports needed.

### Step 3.3: Verify and commit

- [x] **Step 4: Run the verify build**

Run: `mvn -q verify`

Expected: BUILD SUCCESS. The two new IT methods pass alongside everything else.

- [x] **Step 5: Commit**

```bash
git add src/test/resources/openapi.yaml src/test/resources/openapi.json \
        src/test/java/com/retailsvc/http/OpenApiServerIT.java
git commit -m "$(cat <<'EOF'
test: Add integration coverage for boolean schemas via /gates

Extend the OpenAPI fixture with a /gates endpoint whose request body
uses properties.open: true (any value accepted) and properties.blocked:
false (any presence rejected). Two IT cases verify a body with only
'open' returns 200 and a body containing 'blocked' returns 400 with
keyword "false" in the problem detail.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit hook may reformat YAML / Java — re-stage / re-run as needed.

---

## Final verification

- [x] Run `mvn -q verify` once more.
- [x] `git log --oneline master..HEAD` shows the spec commit followed by three feature commits in this order: `feat: Parse boolean schemas…`, `feat: Validate AlwaysSchema and NeverSchema…`, `test: Add integration coverage for boolean schemas…`.
- [x] `grep -rEn "void [a-zA-Z][a-zA-Z0-9]*_[a-zA-Z]" src/test/java/` returns nothing — confirms test method names follow the pure-camelCase convention.

## Out of scope

- `arraySchema.items` empty-map → `NullSchema` quirk (pre-existing).
- `$ref` siblings.
- Combinator branches accepting booleans — only meaningful once `feat/combinators` lands; the parser change here automatically covers it via `parseList`.
- Removing the `/anyOf` and `/allOf` fixture stubs — owned by `feat/combinators`.
