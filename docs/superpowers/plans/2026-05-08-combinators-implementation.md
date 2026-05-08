# Combinators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement runtime validation for OpenAPI 3.1 combinators (`allOf`, `anyOf`, `oneOf`, `not`) and let combinators co-exist with sibling base assertions (`type`, `properties`, etc.) per JSON Schema 2020-12.

**Architecture:** `DefaultValidator` gains four real branches replacing `UnsupportedOperationException`. `SchemaParser` switches from "first matching keyword wins" to "parse base assertions plus each combinator and wrap in implicit `AllOfSchema` when multiple are present". The existing `AllOfSchema` / `AnyOfSchema` / `OneOfSchema` / `NotSchema` records are reused; no new schema kinds.

**Tech Stack:** Java 25, JUnit 5 + AssertJ + Mockito, Maven Surefire/Failsafe, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-08-combinators-design.md`

**Branch:** `feat/combinators` (already checked out).

---

## Task 1: Validator branches + unit tests

**Files:**
- Modify: `src/main/java/com/retailsvc/http/validate/DefaultValidator.java` (lines 64–67 — the four UOE branches)
- Modify: `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java` (replace the `combinatorThrowsUnsupported` test; add new combinator tests)

### Step 1.1: Write failing validator tests

- [x] **Step 1: Add tests for combinator validation**

Replace the existing `combinatorThrowsUnsupported` test in `src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java` with the following block (delete the old test, append the new ones at the end of the class). Keep the existing imports and add: `AllOfSchema`, `AnyOfSchema`, `NotSchema`, `StringSchema`.

```java
// Imports to add at the top of the file:
//   import com.retailsvc.http.spec.schema.AllOfSchema;
//   import com.retailsvc.http.spec.schema.AnyOfSchema;
//   import com.retailsvc.http.spec.schema.NotSchema;
//   import com.retailsvc.http.spec.schema.StringSchema;

private StringSchema stringSchema(Integer min, Integer max) {
  return new StringSchema(Set.of(TypeName.STRING), null, min, max, null, null);
}

@Test
void allOfPassesWhenAllBranchesPass() {
  var schema =
      new AllOfSchema(List.of(stringSchema(1, null), stringSchema(null, 10)));
  v.validate("hello", schema, "/v");
}

@Test
void allOfPropagatesFirstFailingBranch() {
  var schema =
      new AllOfSchema(List.of(stringSchema(1, null), stringSchema(null, 3)));
  assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
      .isInstanceOf(ValidationException.class)
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("maxLength");
}

@Test
void anyOfPassesWhenOneBranchPasses() {
  var schema =
      new AnyOfSchema(List.of(stringSchema(100, null), stringSchema(null, 10)));
  v.validate("hello", schema, "/v");
}

@Test
void anyOfFailsWhenNoBranchMatches() {
  var schema =
      new AnyOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)));
  assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
      .isInstanceOf(ValidationException.class)
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("anyOf");
}

@Test
void oneOfPassesWhenExactlyOneBranchMatches() {
  // value "hello" — len 5. branch[0] requires min 100 (fails), branch[1] max 10 (passes).
  var schema =
      new OneOfSchema(List.of(stringSchema(100, null), stringSchema(null, 10)));
  v.validate("hello", schema, "/v");
}

@Test
void oneOfFailsWhenZeroBranchesMatch() {
  var schema =
      new OneOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)));
  assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
      .isInstanceOf(ValidationException.class)
      .satisfies(
          t -> {
            var err = ((ValidationException) t).error();
            org.assertj.core.api.Assertions.assertThat(err.keyword()).isEqualTo("oneOf");
            org.assertj.core.api.Assertions.assertThat(err.message()).contains("matched 0 of 2");
          });
}

@Test
void oneOfFailsWhenTwoBranchesMatch() {
  // value "hello" — both branches accept.
  var schema =
      new OneOfSchema(List.of(stringSchema(null, 10), stringSchema(1, null)));
  assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
      .isInstanceOf(ValidationException.class)
      .satisfies(
          t -> {
            var err = ((ValidationException) t).error();
            org.assertj.core.api.Assertions.assertThat(err.keyword()).isEqualTo("oneOf");
            org.assertj.core.api.Assertions.assertThat(err.message()).contains("matched 2 of 2");
          });
}

@Test
void notPassesWhenInnerFails() {
  var schema = new NotSchema(stringSchema(100, null));
  v.validate("hello", schema, "/v");
}

@Test
void notFailsWhenInnerPasses() {
  var schema = new NotSchema(stringSchema(null, 10));
  assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
      .isInstanceOf(ValidationException.class)
      .extracting(t -> ((ValidationException) t).error().keyword())
      .isEqualTo("not");
}
```

- [x] **Step 2: Run the failing tests**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest`

Expected: 9 new tests fail with `UnsupportedOperationException` (and the old `combinatorThrowsUnsupported` is deleted, so it does not run).

### Step 1.2: Replace the four UOE branches

- [x] **Step 3: Edit `DefaultValidator.validate(...)`**

Open `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`. Replace these four lines:

```java
case OneOfSchema _ -> throw new UnsupportedOperationException("oneOf not yet supported");
case AnyOfSchema _ -> throw new UnsupportedOperationException("anyOf not yet supported");
case AllOfSchema _ -> throw new UnsupportedOperationException("allOf not yet supported");
case NotSchema _ -> throw new UnsupportedOperationException("not not yet supported");
```

With:

```java
case AllOfSchema(List<Schema> parts) -> {
  for (Schema p : parts) {
    validate(value, p, pointer);
  }
}
case AnyOfSchema(List<Schema> options) -> validateAnyOf(value, options, pointer);
case OneOfSchema(List<Schema> options) -> validateOneOf(value, options, pointer);
case NotSchema(Schema inner) -> validateNot(value, inner, pointer);
```

Then add the three private helper methods at the bottom of the class (before the final closing brace, after `validateArray` or wherever the other private helpers live):

```java
private void validateAnyOf(Object value, List<Schema> options, String pointer) {
  for (Schema o : options) {
    try {
      validate(value, o, pointer);
      return;
    } catch (ValidationException ignored) {
      // try next branch
    }
  }
  fail(pointer, "anyOf", "did not match any anyOf branch", value);
}

private void validateOneOf(Object value, List<Schema> options, String pointer) {
  int matched = 0;
  for (Schema o : options) {
    try {
      validate(value, o, pointer);
      matched++;
    } catch (ValidationException ignored) {
      // count misses
    }
  }
  if (matched != 1) {
    fail(
        pointer,
        "oneOf",
        "matched " + matched + " of " + options.size() + " oneOf branches",
        value);
  }
}

private void validateNot(Object value, Schema inner, String pointer) {
  try {
    validate(value, inner, pointer);
  } catch (ValidationException expected) {
    return;
  }
  fail(pointer, "not", "value matched 'not' schema", value);
}
```

- [x] **Step 4: Run the unit tests**

Run: `mvn -q test -Dtest=DefaultValidatorDispatchTest`

Expected: all tests in the class pass (the original 4 + the 9 new ones).

- [x] **Step 5: Run the full unit test suite**

Run: `mvn -q test`

Expected: BUILD SUCCESS, 119 + 9 = 128 tests pass (or whatever count matches; no failures).

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/validate/DefaultValidator.java \
        src/test/java/com/retailsvc/http/validate/DefaultValidatorDispatchTest.java
git commit -m "$(cat <<'EOF'
feat: Implement combinator validation in DefaultValidator

Replace the four UnsupportedOperationException branches for allOf /
anyOf / oneOf / not with real validation. allOf propagates the first
failing branch; anyOf short-circuits on first match; oneOf evaluates
all branches and asserts exactly one matches; not inverts the inner
result. Adds 9 unit tests covering pass/fail per combinator.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Parser composition + parser tests

**Files:**
- Modify: `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`
- Modify: `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java`

### Step 2.1: Add failing parser tests

- [x] **Step 1: Add tests covering the composition path**

Append to `src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java` (inside the class, before the final `}`):

```java
@Test
void allOfWithSiblingTypeWrapsInImplicitAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "object",
              "required", List.of("x"),
              "allOf", List.of(Map.of("type", "object", "required", List.of("y")))));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  assertThat(all.parts()).hasSize(2);
  assertThat(all.parts().get(0)).isInstanceOf(ObjectSchema.class);
  assertThat(((ObjectSchema) all.parts().get(0)).required()).containsExactly("x");
  assertThat(all.parts().get(1)).isInstanceOf(ObjectSchema.class);
  assertThat(((ObjectSchema) all.parts().get(1)).required()).containsExactly("y");
}

@Test
void anyOfWithSiblingTypeWrapsInImplicitAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "string",
              "anyOf",
                  List.of(
                      Map.of("type", "string", "minLength", 1),
                      Map.of("type", "string", "maxLength", 10))));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  assertThat(all.parts()).hasSize(2);
  assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
  assertThat(all.parts().get(1)).isInstanceOf(AnyOfSchema.class);
  assertThat(((AnyOfSchema) all.parts().get(1)).options()).hasSize(2);
}

@Test
void oneOfWithSiblingTypeWrapsInImplicitAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "string",
              "oneOf",
                  List.of(
                      Map.of("type", "string", "minLength", 1),
                      Map.of("type", "string", "maxLength", 10))));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  assertThat(all.parts()).hasSize(2);
  assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
  assertThat(all.parts().get(1)).isInstanceOf(OneOfSchema.class);
}

@Test
void notWithSiblingTypeWrapsInImplicitAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "string",
              "not", Map.of("type", "string", "maxLength", 2)));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  assertThat(all.parts()).hasSize(2);
  assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
  assertThat(all.parts().get(1)).isInstanceOf(NotSchema.class);
}

@Test
void multipleCombinatorsInOneSchemaWrapInAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer")),
              "not", Map.of("type", "boolean")));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  assertThat(all.parts()).hasSize(2);
  assertThat(all.parts().get(0)).isInstanceOf(AnyOfSchema.class);
  assertThat(all.parts().get(1)).isInstanceOf(NotSchema.class);
}

@Test
void allOfBranchesFlattenIntoOuterAllOf() {
  Schema s =
      SchemaParser.parse(
          Map.of(
              "type", "string",
              "allOf",
                  List.of(
                      Map.of("type", "string", "minLength", 1),
                      Map.of("type", "string", "maxLength", 10))));
  assertThat(s).isInstanceOf(AllOfSchema.class);
  AllOfSchema all = (AllOfSchema) s;
  // Base + the two allOf branches flattened.
  assertThat(all.parts()).hasSize(3);
}

@Test
void aloneCombinatorStillReturnsCombinatorRecord() {
  // Regression: when no base assertions are present, the result is still the
  // single combinator record, not an AllOfSchema with a single child.
  Schema s =
      SchemaParser.parse(
          Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))));
  assertThat(s).isInstanceOf(OneOfSchema.class);
  assertThat(((OneOfSchema) s).options()).hasSize(2);
}
```

- [x] **Step 2: Run the new parser tests (expect failures)**

Run: `mvn -q test -Dtest=SchemaParserTest`

Expected: the 7 new tests fail. The composition tests fail because `parse(...)` currently returns a single combinator record (e.g. `OneOfSchema`), not an `AllOfSchema`. The `aloneCombinatorStillReturnsCombinatorRecord` test currently passes — it's a regression guard for the next step.

### Step 2.2: Rewrite the parser dispatch

- [x] **Step 3: Replace the body of `SchemaParser.parse(Map<String, Object>)`**

Open `src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java`. Replace the entire `parse` method (currently lines 15–55) with:

```java
@SuppressWarnings("unchecked")
public static Schema parse(Map<String, Object> raw) {
  if (raw.containsKey("$ref")) {
    return new RefSchema((String) raw.get("$ref"));
  }

  List<Schema> assertions = new ArrayList<>();

  Schema base = parseBaseIfPresent(raw);
  if (base != null) {
    assertions.add(base);
  }

  if (raw.containsKey("allOf")) {
    assertions.addAll(parseList(raw, "allOf"));
  }
  if (raw.containsKey("anyOf")) {
    assertions.add(new AnyOfSchema(parseList(raw, "anyOf")));
  }
  if (raw.containsKey("oneOf")) {
    assertions.add(new OneOfSchema(parseList(raw, "oneOf")));
  }
  if (raw.containsKey("not")) {
    assertions.add(new NotSchema(parse((Map<String, Object>) raw.get("not"))));
  }

  return switch (assertions.size()) {
    case 0 -> permissiveObject();
    case 1 -> assertions.getFirst();
    default -> new AllOfSchema(List.copyOf(assertions));
  };
}

@SuppressWarnings("unchecked")
private static Schema parseBaseIfPresent(Map<String, Object> raw) {
  if (raw.containsKey("const")) {
    return new ConstSchema(raw.get("const"));
  }
  if (raw.containsKey("enum") && !raw.containsKey("type")) {
    return new EnumSchema(List.copyOf((List<Object>) raw.get("enum")));
  }

  Set<TypeName> types = parseTypes(raw);
  if (types.isEmpty() && !hasObjectShapeKeywords(raw) && !hasArrayShapeKeywords(raw)) {
    return null;
  }

  TypeName primary =
      types.stream().filter(t -> t != TypeName.NULL).findFirst().orElse(TypeName.NULL);

  if (types.isEmpty() && hasObjectShapeKeywords(raw)) {
    return parseObject(raw, types);
  }
  if (types.isEmpty() && hasArrayShapeKeywords(raw)) {
    return parseArray(raw, types);
  }

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

private static boolean hasObjectShapeKeywords(Map<String, Object> raw) {
  return raw.containsKey("properties")
      || raw.containsKey("required")
      || raw.containsKey("additionalProperties")
      || raw.containsKey("minProperties")
      || raw.containsKey("maxProperties");
}

private static boolean hasArrayShapeKeywords(Map<String, Object> raw) {
  return raw.containsKey("items")
      || raw.containsKey("minItems")
      || raw.containsKey("maxItems")
      || raw.containsKey("uniqueItems");
}

private static Schema permissiveObject() {
  return new ObjectSchema(
      Set.of(),
      Map.of(),
      List.of(),
      new AdditionalProperties.Allowed(),
      null,
      null);
}
```

The key behaviour changes:
- `$ref` still returns immediately and ignores siblings (existing limitation, out of scope here).
- `parseBaseIfPresent` returns the existing primitive/object/array record when there is *any* base assertion in the schema map, and `null` when there is none. It also handles "implicit object" (`{required: [...]}` without `type`) and "implicit array" (`{items: ...}` without `type`) so those continue to parse as before when they appear without combinators.
- The four combinator keywords are then each appended to the assertions list. `allOf` is flattened (its branches join the outer list directly), the others wrap.
- A single assertion returns unwrapped; two or more wrap in `AllOfSchema`.

- [x] **Step 4: Run the parser tests**

Run: `mvn -q test -Dtest=SchemaParserTest`

Expected: all `SchemaParserTest` tests pass, including the 7 new ones.

- [x] **Step 5: Run the full unit suite**

Run: `mvn -q test`

Expected: BUILD SUCCESS, no regressions in `ContainerSchemasTest`, `PrimitiveSchemasTest`, `CombinatorScaffoldTest`, etc.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/schema/SchemaParser.java \
        src/test/java/com/retailsvc/http/spec/schema/SchemaParserTest.java
git commit -m "$(cat <<'EOF'
feat: Compose combinators with sibling base assertions in SchemaParser

Previously the parser dispatched in priority order and emitted exactly
one schema record, silently discarding combinators that coexisted with
type / properties / etc. Now each combinator and any base assertion
contribute to an assertions list; multiple assertions wrap in an
implicit AllOfSchema. allOf branches flatten into the outer list.

Adds 7 parser tests for the composition path and a regression guard
that a lone combinator still returns the bare combinator record.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Integration test — polymorphic body via `oneOf`

**Files:**
- Modify: `src/test/resources/openapi.yaml`
- Modify: `src/test/resources/openapi.json`
- Create: `src/test/java/com/retailsvc/http/start/PolymorphicHandler.java`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerIT.java`

### Step 3.1: Extend the OpenAPI fixture

The fixture has stub `/anyOf` and `/allOf` paths with empty `post: {}`. Replace them with a real `/oneOf` route exercising a polymorphic body.

- [x] **Step 1: Replace the stub paths in `openapi.yaml`**

Open `src/test/resources/openapi.yaml`. Find the lines:

```yaml
  /anyOf:
    post: {}

  /allOf:
    post: {}
```

Replace with:

```yaml
  /shapes:
    post:
      operationId: post-shape
      requestBody:
        required: true
        content:
          application/json:
            schema:
              oneOf:
                - type: object
                  required: [kind, radius]
                  properties:
                    kind:
                      type: string
                      enum: [circle]
                    radius:
                      type: number
                - type: object
                  required: [kind, side]
                  properties:
                    kind:
                      type: string
                      enum: [square]
                    side:
                      type: number
      responses:
        "200":
          description: OK
```

- [x] **Step 2: Mirror the change in `openapi.json`**

Open `src/test/resources/openapi.json`. Locate the `"/anyOf"` and `"/allOf"` keys (lines ~173 and ~178). Replace both blocks with a single `"/shapes"` entry equivalent to the YAML above. Use the existing JSON formatting (2-space indent, `application/json` media type). Worked example:

```json
    "/shapes": {
      "post": {
        "operationId": "post-shape",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "oneOf": [
                  {
                    "type": "object",
                    "required": ["kind", "radius"],
                    "properties": {
                      "kind": { "type": "string", "enum": ["circle"] },
                      "radius": { "type": "number" }
                    }
                  },
                  {
                    "type": "object",
                    "required": ["kind", "side"],
                    "properties": {
                      "kind": { "type": "string", "enum": ["square"] },
                      "side": { "type": "number" }
                    }
                  }
                ]
              }
            }
          }
        },
        "responses": { "200": { "description": "OK" } }
      }
    }
```

Make sure the trailing comma on the previous path entry (or this one) is correct so the JSON still parses.

### Step 3.2: Add a test handler

- [x] **Step 3: Create `PolymorphicHandler.java`**

Create `src/test/java/com/retailsvc/http/start/PolymorphicHandler.java`:

```java
package com.retailsvc.http.start;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PolymorphicHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (var out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
```

### Step 3.3: Add the integration test

- [x] **Step 4: Add tests to `OpenApiServerIT.java`**

Open `src/test/java/com/retailsvc/http/OpenApiServerIT.java`. Add a new nested class at the bottom of the outer class (immediately before the final `}` of `OpenApiServerIT`):

```java
@Nested
class Shapes {

  String path = "/shapes";

  @Test
  void postShape_validCircleReturns200() {
    try (var server = newServer(Map.of("post-shape", new com.retailsvc.http.start.PolymorphicHandler()));
        var client = httpClient()) {
      var body = "{\"kind\":\"circle\",\"radius\":2.5}";
      var request = newRequest(server, path, "POST", ofString(body));

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"ok\":true");
    } catch (IOException e) {
      fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e);
    }
  }

  @Test
  void postShape_validSquareReturns200() {
    try (var server = newServer(Map.of("post-shape", new com.retailsvc.http.start.PolymorphicHandler()));
        var client = httpClient()) {
      var body = "{\"kind\":\"square\",\"side\":3}";
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
  void postShape_unknownKindReturns400() {
    // matches zero branches: "kind" is neither "circle" nor "square".
    try (var server = newServer(Map.of("post-shape", new com.retailsvc.http.start.PolymorphicHandler()));
        var client = httpClient()) {
      var body = "{\"kind\":\"triangle\",\"side\":3}";
      var request = newRequest(server, path, "POST", ofString(body));

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type").orElse(""))
          .contains("application/problem+json");
      assertThat(response.body()).contains("oneOf");
    } catch (IOException e) {
      fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e);
    }
  }

  @Test
  void postShape_missingDiscriminatorReturns400() {
    // omitting "kind" makes both branches fail "required".
    try (var server = newServer(Map.of("post-shape", new com.retailsvc.http.start.PolymorphicHandler()));
        var client = httpClient()) {
      var body = "{\"radius\":2.5}";
      var request = newRequest(server, path, "POST", ofString(body));

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(400);
    } catch (IOException e) {
      fail(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e);
    }
  }
}
```

The `ofString`, `BodyHandlers`, and `assertThat` imports are already present at the top of `OpenApiServerIT.java`; no new imports needed beyond `org.junit.jupiter.api.Nested` (also already present) and `java.io.IOException` (already imported).

- [x] **Step 5: Run the integration tests**

Run: `mvn -q verify -DfailIfNoTests=false -Dtest='!*' -Dit.test=OpenApiServerIT`

Or simply: `mvn -q verify`

Expected: BUILD SUCCESS. The four new IT cases all pass.

- [x] **Step 6: Run the full verify**

Run: `mvn -q verify`

Expected: BUILD SUCCESS. All previous unit tests + the 9 new validator tests + the 7 new parser tests + the 4 new IT tests pass.

- [x] **Step 7: Commit**

```bash
git add src/test/resources/openapi.yaml src/test/resources/openapi.json \
        src/test/java/com/retailsvc/http/start/PolymorphicHandler.java \
        src/test/java/com/retailsvc/http/OpenApiServerIT.java
git commit -m "$(cat <<'EOF'
test: Add polymorphic-body integration coverage for oneOf

Replace stub /anyOf and /allOf fixture routes with a real /shapes
endpoint that uses oneOf to discriminate between circle and square
request bodies. Adds an integration test verifying both valid
branches return 200 and that bodies matching zero branches return
400 application/problem+json with the oneOf keyword in the body.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [x] Run `mvn -q verify` once more.
- [x] `git log --oneline master..HEAD` shows three new commits in this order: `feat: Implement combinator validation…`, `feat: Compose combinators with sibling…`, `test: Add polymorphic-body integration…`.
- [x] No new files exist outside the paths listed above.

## Out of scope (do not do)

- Schema booleans (`true` / `false` as a bare schema) — needs `SchemaParser.parse(...)` to accept a non-Map raw value.
- `discriminator` keyword — separate Wave 6 follow-up.
- Multi-error collection — separate Wave 6 follow-up.
- Internal `boolean tryValidate(...)` overload to avoid throw/catch in `oneOf`/`anyOf` — only worth doing if profiling shows it.
- `$ref` siblings — existing limitation, untouched.
