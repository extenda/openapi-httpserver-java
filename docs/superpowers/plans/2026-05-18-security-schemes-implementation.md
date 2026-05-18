# Security schemes (OpenAPI 3.1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenAPI 3.1 `securitySchemes` + `security` support: library parses the metadata, extracts credentials, lets the consumer validate via a name-keyed callback, renders RFC 7807 rejections (401/403), and offers an `useExternalAuthentication()` opt-out for sidecar deployments.

**Architecture:** New typed model under `com.retailsvc.http.spec.security` for schemes and requirements. New `SecurityFilter` in `com.retailsvc.http.internal` between `RequestPreparationFilter` and `DispatchHandler`. Consumer-facing types `Credential` (sealed) and `SchemeValidator` (functional interface) on the public surface. `Request` grows an immutable `withPrincipals(...)` factory; principals stash via the existing `ScopedValue` rebinding pattern.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver`, JUnit 5, AssertJ, Mockito, Surefire/Failsafe. Code formatted by Google Java Format (pre-commit). Tests use static imports, camelCase method names, curly braces always.

**Spec:** `docs/superpowers/specs/2026-05-18-security-schemes-design.md`.

---

## File Structure

**New files (production):**

- `src/main/java/com/retailsvc/http/spec/security/SecurityScheme.java` — sealed interface + records (`ApiKey`, `HttpBearer`, `HttpBasic`, `Unsupported`) + `Location` enum.
- `src/main/java/com/retailsvc/http/spec/security/SecurityRequirement.java` — record wrapping `Map<String, List<String>>`.
- `src/main/java/com/retailsvc/http/spec/security/SecuritySchemeParser.java` — static helpers to parse raw maps into the typed model.
- `src/main/java/com/retailsvc/http/Credential.java` — sealed interface + `ApiKeyCredential`, `BearerCredential`, `BasicCredential` records.
- `src/main/java/com/retailsvc/http/SchemeValidator.java` — `@FunctionalInterface` `Optional<Object> validate(Request, Credential)`.
- `src/main/java/com/retailsvc/http/internal/CredentialExtractor.java` — package-private; given a scheme + exchange returns `ExtractionResult`.
- `src/main/java/com/retailsvc/http/internal/SecurityFilter.java` — the new filter.

**Modified files (production):**

- `src/main/java/com/retailsvc/http/spec/Spec.java` — add `securitySchemes` + `security` record components; parse in `Spec.from(...)`.
- `src/main/java/com/retailsvc/http/spec/Operation.java` — add `Optional<List<SecurityRequirement>> security`.
- `src/main/java/com/retailsvc/http/Request.java` — add `principals` + `principal(String)` + `withPrincipals(Map)`.
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — wire `SecurityFilter` into the chain, boot-time validation.
- `src/main/java/com/retailsvc/http/OpenApiServerBuilder.java` (or wherever `Builder` lives) — `securityValidator(name, validator)` + `useExternalAuthentication()`.

**New files (tests):**

- `src/test/java/com/retailsvc/http/spec/security/SchemeParserTest.java`
- `src/test/java/com/retailsvc/http/spec/security/SecurityRequirementParseTest.java`
- `src/test/java/com/retailsvc/http/internal/CredentialExtractorTest.java`
- `src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java`
- `src/test/java/com/retailsvc/http/SecurityBootValidationTest.java`
- `src/test/java/com/retailsvc/http/SecurityIT.java`
- `src/test/resources/security/openapi-secured.json` — fixture with op-level overrides + root-level inheritance.

**Modified fixtures:**

- `src/test/resources/openapi.json` and `openapi.yaml` — append `/api/v1/secure/*` operations under a new path prefix. **No root-level `security`.**

---

## Task 1: Spec model — `SecurityScheme` sealed interface

**Files:**

- Create: `src/main/java/com/retailsvc/http/spec/security/SecurityScheme.java`
- Test: `src/test/java/com/retailsvc/http/spec/security/SchemeParserTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemeParserTest {

  @Test
  void apiKeyHeaderParses() {
    var scheme =
        SecuritySchemeParser.parse(java.util.Map.of("type", "apiKey", "name", "X-API-Key", "in", "header"));
    assertThat(scheme).isEqualTo(new ApiKey("X-API-Key", Location.HEADER));
  }

  @Test
  void httpBearerParses() {
    var scheme =
        SecuritySchemeParser.parse(java.util.Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
    assertThat(scheme).isEqualTo(new SecurityScheme.HttpBearer(Optional.of("JWT")));
  }

  @Test
  void httpBasicParses() {
    var scheme = SecuritySchemeParser.parse(java.util.Map.of("type", "http", "scheme", "basic"));
    assertThat(scheme).isEqualTo(new SecurityScheme.HttpBasic());
  }

  @Test
  void unknownTypeMapsToUnsupported() {
    var scheme = SecuritySchemeParser.parse(java.util.Map.of("type", "oauth2"));
    assertThat(scheme).isEqualTo(new SecurityScheme.Unsupported("oauth2"));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SchemeParserTest`
Expected: FAIL — `SecurityScheme` and `SecuritySchemeParser` don't exist yet.

- [ ] **Step 3: Create `SecurityScheme.java`**

```java
package com.retailsvc.http.spec.security;

import java.util.Optional;

public sealed interface SecurityScheme
    permits SecurityScheme.ApiKey,
        SecurityScheme.HttpBearer,
        SecurityScheme.HttpBasic,
        SecurityScheme.Unsupported {

  record ApiKey(String name, Location location) implements SecurityScheme {
    public enum Location {
      HEADER,
      QUERY,
      COOKIE
    }
  }

  record HttpBearer(Optional<String> bearerFormat) implements SecurityScheme {}

  record HttpBasic() implements SecurityScheme {}

  /** Parsed but unsupported in v1 (oauth2, openIdConnect, mutualTLS). */
  record Unsupported(String type) implements SecurityScheme {}
}
```

- [ ] **Step 4: Create `SecuritySchemeParser.java` minimal version**

```java
package com.retailsvc.http.spec.security;

import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.retailsvc.http.spec.security.SecurityScheme.Unsupported;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SecuritySchemeParser {
  private SecuritySchemeParser() {}

  public static SecurityScheme parse(Map<String, Object> raw) {
    String type = (String) raw.get("type");
    if (type == null) {
      throw new IllegalArgumentException("securityScheme missing required 'type'");
    }
    return switch (type) {
      case "apiKey" -> parseApiKey(raw);
      case "http" -> parseHttp(raw);
      default -> new Unsupported(type);
    };
  }

  private static SecurityScheme parseApiKey(Map<String, Object> raw) {
    String name = (String) raw.get("name");
    String in = (String) raw.get("in");
    if (name == null || in == null) {
      throw new IllegalArgumentException("apiKey scheme requires 'name' and 'in'");
    }
    return new ApiKey(name, Location.valueOf(in.toUpperCase(Locale.ROOT)));
  }

  private static SecurityScheme parseHttp(Map<String, Object> raw) {
    String scheme = (String) raw.get("scheme");
    if (scheme == null) {
      throw new IllegalArgumentException("http securityScheme requires 'scheme'");
    }
    return switch (scheme.toLowerCase(Locale.ROOT)) {
      case "bearer" -> new HttpBearer(Optional.ofNullable((String) raw.get("bearerFormat")));
      case "basic" -> new HttpBasic();
      default -> new Unsupported("http:" + scheme);
    };
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=SchemeParserTest`
Expected: 4 tests pass.

- [ ] **Step 6: Run SonarLint on the new files**

Use the `mcp__sonarlint__sonar_analyze_file` tool for each new file. Fix any reported issues before committing (per the global rule in `~/.claude/memory/feedback_sonar_pre_push.md`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/security/SecurityScheme.java \
        src/main/java/com/retailsvc/http/spec/security/SecuritySchemeParser.java \
        src/test/java/com/retailsvc/http/spec/security/SchemeParserTest.java
git commit -m "feat(spec): Add SecurityScheme sealed model + parser"
```

---

## Task 2: Spec model — `SecurityRequirement` + parser

**Files:**

- Create: `src/main/java/com/retailsvc/http/spec/security/SecurityRequirement.java`
- Modify: `src/main/java/com/retailsvc/http/spec/security/SecuritySchemeParser.java` (add `parseRequirements`)
- Test: `src/test/java/com/retailsvc/http/spec/security/SecurityRequirementParseTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.spec.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityRequirementParseTest {

  @Test
  void singleRequirementParses() {
    List<Object> raw = List.of(Map.of("bearerAuth", List.of()));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req)
        .containsExactly(new SecurityRequirement(Map.of("bearerAuth", List.of())));
  }

  @Test
  void andGroupParses() {
    List<Object> raw = List.of(Map.of("apiKey", List.of(), "bearer", List.of("admin")));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req).hasSize(1);
    assertThat(req.get(0).schemes())
        .containsEntry("apiKey", List.of())
        .containsEntry("bearer", List.of("admin"));
  }

  @Test
  void orGroupsParse() {
    List<Object> raw =
        List.of(Map.of("apiKey", List.of()), Map.of("bearer", List.of()));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req).hasSize(2);
  }

  @Test
  void nullReturnsEmptyList() {
    assertThat(SecuritySchemeParser.parseRequirements(null)).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SecurityRequirementParseTest`
Expected: FAIL — `SecurityRequirement` and `parseRequirements` don't exist.

- [ ] **Step 3: Create `SecurityRequirement.java`**

```java
package com.retailsvc.http.spec.security;

import java.util.List;
import java.util.Map;

/**
 * One OR-branch in a {@code security} list. Each entry in {@link #schemes} is AND-ed: every scheme
 * name must be satisfied for the requirement to hold. Scopes are preserved but unused in v1.
 */
public record SecurityRequirement(Map<String, List<String>> schemes) {
  public SecurityRequirement {
    schemes = Map.copyOf(schemes);
  }
}
```

- [ ] **Step 4: Add `parseRequirements` to `SecuritySchemeParser.java`**

Append to the parser class:

```java
@SuppressWarnings("unchecked")
public static List<SecurityRequirement> parseRequirements(List<Object> raw) {
  if (raw == null || raw.isEmpty()) {
    return List.of();
  }
  List<SecurityRequirement> out = new java.util.ArrayList<>(raw.size());
  for (Object entry : raw) {
    if (!(entry instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("security requirement entries must be objects");
    }
    Map<String, List<String>> schemes = new java.util.LinkedHashMap<>();
    for (var e : map.entrySet()) {
      String name = (String) e.getKey();
      List<String> scopes =
          e.getValue() instanceof List<?> list
              ? list.stream().map(Object::toString).toList()
              : List.of();
      schemes.put(name, scopes);
    }
    out.add(new SecurityRequirement(schemes));
  }
  return List.copyOf(out);
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=SecurityRequirementParseTest`
Expected: 4 tests pass.

- [ ] **Step 6: Run SonarLint on the modified parser file. Fix any issues.**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/security/SecurityRequirement.java \
        src/main/java/com/retailsvc/http/spec/security/SecuritySchemeParser.java \
        src/test/java/com/retailsvc/http/spec/security/SecurityRequirementParseTest.java
git commit -m "feat(spec): Add SecurityRequirement model + parser"
```

---

## Task 3: Wire `securitySchemes` + root `security` into `Spec`

**Files:**

- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java`
- Test: `src/test/java/com/retailsvc/http/spec/SpecTest.java` (add cases; do not rewrite existing)

- [ ] **Step 1: Write failing tests**

Append to `SpecTest.java`:

```java
@Test
void parsesSecuritySchemesFromComponents() {
  Map<String, Object> raw = Map.of(
      "openapi", "3.1.0",
      "info", Map.of("title", "T", "version", "1"),
      "servers", List.of(Map.of("url", "/v1")),
      "paths", Map.of(),
      "components",
          Map.of(
              "securitySchemes",
              Map.of("apiKeyAuth", Map.of("type", "apiKey", "name", "X-API-Key", "in", "header"))));

  Spec spec = Spec.from(raw);

  assertThat(spec.securitySchemes())
      .containsKey("apiKeyAuth");
}

@Test
void parsesRootSecurity() {
  Map<String, Object> raw = Map.of(
      "openapi", "3.1.0",
      "info", Map.of("title", "T", "version", "1"),
      "servers", List.of(Map.of("url", "/v1")),
      "paths", Map.of(),
      "security", List.of(Map.of("bearerAuth", List.of())));

  Spec spec = Spec.from(raw);

  assertThat(spec.security()).hasSize(1);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=SpecTest#parsesSecuritySchemesFromComponents+parsesRootSecurity`
Expected: FAIL — `securitySchemes()` and `security()` don't exist on `Spec`.

- [ ] **Step 3: Extend the `Spec` record**

In `Spec.java`, add two components to the record:

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
    Map<String, Object> extensions,
    Map<String, com.retailsvc.http.spec.security.SecurityScheme> securitySchemes,
    List<com.retailsvc.http.spec.security.SecurityRequirement> security) {
```

(Add proper `import` statements so the FQN inline isn't needed — per the global memory rule "no inline fully-qualified type names".)

- [ ] **Step 4: Populate them in `Spec.from(...)`**

In `Spec.from(...)`, before the final `return new Spec(...)`, build:

```java
Map<String, Object> components = (Map<String, Object>) raw.getOrDefault("components", Map.of());
Map<String, Object> rawSchemes =
    (Map<String, Object>) components.getOrDefault("securitySchemes", Map.of());
Map<String, SecurityScheme> securitySchemes = new java.util.LinkedHashMap<>();
for (var e : rawSchemes.entrySet()) {
  securitySchemes.put(e.getKey(), SecuritySchemeParser.parse((Map<String, Object>) e.getValue()));
}

List<SecurityRequirement> rootSecurity =
    SecuritySchemeParser.parseRequirements((List<Object>) raw.get("security"));
```

And pass `Map.copyOf(securitySchemes), rootSecurity` as the new args to `new Spec(...)`.

- [ ] **Step 5: Fix any other compile errors**

Other call sites of `new Spec(...)` (in test helpers if any) will not compile. Search:

```bash
grep -rn "new Spec(" src/
```

Add `Map.of(), List.of()` as the trailing args.

- [ ] **Step 6: Run all tests**

Run: `mvn test`
Expected: BUILD SUCCESS, all existing tests still pass, two new tests pass.

- [ ] **Step 7: Run SonarLint on `Spec.java`. Fix any new issues.**

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Spec.java \
        src/test/java/com/retailsvc/http/spec/SpecTest.java
git commit -m "feat(spec): Parse securitySchemes + root security into Spec"
```

---

## Task 4: Per-operation `security` on `Operation`

**Files:**

- Modify: `src/main/java/com/retailsvc/http/spec/Operation.java`
- Modify: `src/main/java/com/retailsvc/http/spec/Spec.java` (operation parsing path)
- Test: append to `SpecTest.java`

- [ ] **Step 1: Write failing tests**

Append to `SpecTest.java`:

```java
@Test
void operationLevelSecurityOverridesRoot() {
  Map<String, Object> raw = Map.of(
      "openapi", "3.1.0",
      "info", Map.of("title", "T", "version", "1"),
      "servers", List.of(Map.of("url", "/v1")),
      "security", List.of(Map.of("bearerAuth", List.of())),
      "paths",
          Map.of(
              "/x",
              Map.of(
                  "get",
                  Map.of(
                      "operationId", "getX",
                      "security", List.of(Map.of("apiKey", List.of())),
                      "responses", Map.of("200", Map.of("description", "ok"))))));

  Spec spec = Spec.from(raw);
  Operation op = spec.operations().getFirst();

  assertThat(op.security()).isPresent();
  assertThat(op.security().get()).hasSize(1);
  assertThat(op.security().get().get(0).schemes()).containsKey("apiKey");
}

@Test
void operationEmptySecurityIsPreserved() {
  Map<String, Object> raw = Map.of(
      "openapi", "3.1.0",
      "info", Map.of("title", "T", "version", "1"),
      "servers", List.of(Map.of("url", "/v1")),
      "security", List.of(Map.of("bearerAuth", List.of())),
      "paths",
          Map.of(
              "/x",
              Map.of(
                  "get",
                  Map.of(
                      "operationId", "getX",
                      "security", List.of(),
                      "responses", Map.of("200", Map.of("description", "ok"))))));

  Spec spec = Spec.from(raw);
  Operation op = spec.operations().getFirst();

  assertThat(op.security()).isPresent();
  assertThat(op.security().get()).isEmpty();
}

@Test
void operationWithoutSecurityIsEmptyOptional() {
  Map<String, Object> raw = Map.of(
      "openapi", "3.1.0",
      "info", Map.of("title", "T", "version", "1"),
      "servers", List.of(Map.of("url", "/v1")),
      "paths",
          Map.of(
              "/x",
              Map.of(
                  "get",
                  Map.of(
                      "operationId", "getX",
                      "responses", Map.of("200", Map.of("description", "ok"))))));

  Spec spec = Spec.from(raw);
  Operation op = spec.operations().getFirst();

  assertThat(op.security()).isEmpty();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=SpecTest`
Expected: 3 new tests FAIL.

- [ ] **Step 3: Extend `Operation.java`**

```java
package com.retailsvc.http.spec;

import com.retailsvc.http.spec.security.SecurityRequirement;
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
    Map<String, Object> extensions,
    Optional<List<SecurityRequirement>> security) {}
```

- [ ] **Step 4: Update the operation-parsing call site in `Spec.java`**

Find the `new Operation(...)` construction in `Spec.from(...)` (likely inside the path-iteration loop). For each operation, parse:

```java
Optional<List<SecurityRequirement>> opSecurity =
    rawOp.containsKey("security")
        ? Optional.of(SecuritySchemeParser.parseRequirements((List<Object>) rawOp.get("security")))
        : Optional.empty();
```

…and append `opSecurity` as the last argument to `new Operation(...)`.

- [ ] **Step 5: Fix other Operation construction sites if any**

```bash
grep -rn "new Operation(" src/
```

Append `Optional.empty()` to each.

- [ ] **Step 6: Run all tests**

Run: `mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: SonarLint scan + fix**

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/retailsvc/http/spec/Operation.java \
        src/main/java/com/retailsvc/http/spec/Spec.java \
        src/test/java/com/retailsvc/http/spec/SpecTest.java
git commit -m "feat(spec): Add per-operation security with root-override semantics"
```

---

## Task 5: Public API — `Credential` sealed + `SchemeValidator`

**Files:**

- Create: `src/main/java/com/retailsvc/http/Credential.java`
- Create: `src/main/java/com/retailsvc/http/SchemeValidator.java`
- Test: none yet — these are just type declarations, covered by Task 6+.

- [ ] **Step 1: Create `Credential.java`**

```java
package com.retailsvc.http;

/**
 * A credential the library has extracted from a request, ready to hand to a {@link SchemeValidator}.
 * Sealed so consumers can pattern-match across scheme types.
 */
public sealed interface Credential
    permits Credential.ApiKeyCredential, Credential.BearerCredential, Credential.BasicCredential {

  record ApiKeyCredential(String value) implements Credential {}

  record BearerCredential(String token) implements Credential {}

  record BasicCredential(String username, String password) implements Credential {}
}
```

- [ ] **Step 2: Create `SchemeValidator.java`**

```java
package com.retailsvc.http;

import java.util.Optional;

/**
 * Consumer-provided callback that validates an extracted {@link Credential}. Return a non-empty
 * {@link Optional} carrying the principal on success, or {@link Optional#empty()} to deny.
 */
@FunctionalInterface
public interface SchemeValidator {
  Optional<Object> validate(Request request, Credential credential);
}
```

- [ ] **Step 3: Compile**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/retailsvc/http/Credential.java \
        src/main/java/com/retailsvc/http/SchemeValidator.java
git commit -m "feat: Add public Credential sealed type + SchemeValidator interface"
```

---

## Task 6: `CredentialExtractor` (per-scheme extraction)

**Files:**

- Create: `src/main/java/com/retailsvc/http/internal/CredentialExtractor.java`
- Test: `src/test/java/com/retailsvc/http/internal/CredentialExtractorTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.Credential;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialExtractorTest {

  private HttpExchange exchangeWithHeader(String key, String value, String query) {
    HttpExchange ex = mock(HttpExchange.class);
    Headers h = new Headers();
    if (value != null) {
      h.add(key, value);
    }
    when(ex.getRequestHeaders()).thenReturn(h);
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/x" + (query == null ? "" : "?" + query)));
    return ex;
  }

  @Test
  void apiKeyHeaderPresentExtracts() {
    var scheme = new ApiKey("X-API-Key", Location.HEADER);
    var ex = exchangeWithHeader("X-API-Key", "abc123", null);
    var result = CredentialExtractor.extract(scheme, ex);
    assertThat(result).isEqualTo(ExtractionResult.found(new Credential.ApiKeyCredential("abc123")));
  }

  @Test
  void apiKeyHeaderMissingReturnsMissing() {
    var scheme = new ApiKey("X-API-Key", Location.HEADER);
    var ex = exchangeWithHeader("Other", "irrelevant", null);
    assertThat(CredentialExtractor.extract(scheme, ex)).isEqualTo(ExtractionResult.missing());
  }

  @Test
  void apiKeyQueryExtracts() {
    var scheme = new ApiKey("k", Location.QUERY);
    var ex = exchangeWithHeader("Ignored", null, "k=v1&other=v2");
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.ApiKeyCredential("v1")));
  }

  @Test
  void httpBearerPresentExtracts() {
    var scheme = new HttpBearer(Optional.empty());
    var ex = exchangeWithHeader("Authorization", "Bearer abc.def.ghi", null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BearerCredential("abc.def.ghi")));
  }

  @Test
  void httpBearerCaseInsensitive() {
    var scheme = new HttpBearer(Optional.empty());
    var ex = exchangeWithHeader("Authorization", "bEaReR token", null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BearerCredential("token")));
  }

  @Test
  void httpBasicValidBase64Extracts() {
    var scheme = new HttpBasic();
    String creds = java.util.Base64.getEncoder().encodeToString("alice:s3cret".getBytes());
    var ex = exchangeWithHeader("Authorization", "Basic " + creds, null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BasicCredential("alice", "s3cret")));
  }

  @Test
  void httpBasicMalformedBase64ReturnsMalformed() {
    var scheme = new HttpBasic();
    var ex = exchangeWithHeader("Authorization", "Basic !!!not-base64", null);
    assertThat(CredentialExtractor.extract(scheme, ex)).isEqualTo(ExtractionResult.malformed());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=CredentialExtractorTest`
Expected: FAIL — `CredentialExtractor` and `ExtractionResult` don't exist.

- [ ] **Step 3: Create `ExtractionResult.java`** (inside `internal` package, package-private)

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.Credential;
import java.util.Optional;

record ExtractionResult(Kind kind, Credential credential) {
  enum Kind {
    FOUND,
    MISSING,
    MALFORMED
  }

  static ExtractionResult found(Credential credential) {
    return new ExtractionResult(Kind.FOUND, credential);
  }

  static ExtractionResult missing() {
    return new ExtractionResult(Kind.MISSING, null);
  }

  static ExtractionResult malformed() {
    return new ExtractionResult(Kind.MALFORMED, null);
  }
}
```

- [ ] **Step 4: Create `CredentialExtractor.java`**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.Credential;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

final class CredentialExtractor {
  private CredentialExtractor() {}

  static ExtractionResult extract(SecurityScheme scheme, HttpExchange exchange) {
    return switch (scheme) {
      case ApiKey ak -> extractApiKey(ak, exchange);
      case HttpBearer _ -> extractBearer(exchange);
      case HttpBasic _ -> extractBasic(exchange);
      case SecurityScheme.Unsupported _ ->
          throw new IllegalStateException(
              "extractor called with Unsupported scheme — should be caught at boot");
    };
  }

  private static ExtractionResult extractApiKey(ApiKey scheme, HttpExchange exchange) {
    String value =
        switch (scheme.location()) {
          case HEADER -> exchange.getRequestHeaders().getFirst(scheme.name());
          case QUERY -> firstQueryValue(exchange.getRequestURI().getRawQuery(), scheme.name());
          case COOKIE -> firstCookieValue(exchange, scheme.name());
        };
    return value == null
        ? ExtractionResult.missing()
        : ExtractionResult.found(new Credential.ApiKeyCredential(value));
  }

  private static ExtractionResult extractBearer(HttpExchange exchange) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null) {
      return ExtractionResult.missing();
    }
    String[] parts = auth.split("\\s+", 2);
    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
      return ExtractionResult.missing();
    }
    return ExtractionResult.found(new Credential.BearerCredential(parts[1]));
  }

  private static ExtractionResult extractBasic(HttpExchange exchange) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null) {
      return ExtractionResult.missing();
    }
    String[] parts = auth.split("\\s+", 2);
    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Basic")) {
      return ExtractionResult.missing();
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      return ExtractionResult.malformed();
    }
    String creds = new String(decoded, StandardCharsets.UTF_8);
    int sep = creds.indexOf(':');
    if (sep < 0) {
      return ExtractionResult.malformed();
    }
    return ExtractionResult.found(
        new Credential.BasicCredential(creds.substring(0, sep), creds.substring(sep + 1)));
  }

  private static String firstQueryValue(String rawQuery, String name) {
    if (rawQuery == null) {
      return null;
    }
    String prefix = name + "=";
    for (String pair : rawQuery.split("&")) {
      if (pair.startsWith(prefix)) {
        return java.net.URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String firstCookieValue(HttpExchange exchange, String name) {
    String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookieHeader == null) {
      return null;
    }
    for (String pair : cookieHeader.split(";")) {
      String trimmed = pair.trim();
      if (trimmed.startsWith(name + "=")) {
        return trimmed.substring(name.length() + 1);
      }
    }
    return null;
  }
}
```

(`Locale` import is for future safety with case handling; remove if Sonar flags it as unused.)

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=CredentialExtractorTest`
Expected: 7 tests pass.

- [ ] **Step 6: SonarLint scan + fix**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/CredentialExtractor.java \
        src/main/java/com/retailsvc/http/internal/ExtractionResult.java \
        src/test/java/com/retailsvc/http/internal/CredentialExtractorTest.java
git commit -m "feat(internal): Add CredentialExtractor for apiKey/bearer/basic schemes"
```

---

## Task 7: `Request.withPrincipals(...)` + `principal(...)`

**Files:**

- Modify: `src/main/java/com/retailsvc/http/Request.java`
- Test: append to `src/test/java/com/retailsvc/http/RequestTest.java`

- [ ] **Step 1: Write failing tests**

Append to `RequestTest.java`:

```java
@Test
void requestPrincipalsDefaultsEmpty() {
  Request r = newMinimalRequest();
  assertThat(r.principals()).isEmpty();
  assertThat(r.principal("anything")).isEmpty();
}

@Test
void withPrincipalsCreatesCopy() {
  Request r = newMinimalRequest();
  Map<String, Object> principals = Map.of("bearerAuth", "user-123");
  Request withP = r.withPrincipals(principals);

  assertThat(withP).isNotSameAs(r);
  assertThat(r.principals()).isEmpty();
  assertThat(withP.principals()).isEqualTo(principals);
  assertThat(withP.principal("bearerAuth")).contains("user-123");
}

// Helper — match the rest of the file's style; create with whatever the
// existing tests use. If a builder exists, reuse it; otherwise construct
// directly with empty/dummy values for body, mapper, etc.
private Request newMinimalRequest() {
  // ... use the same constructor signature the existing tests use
}
```

(If `RequestTest.java` already has a helper, use that.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=RequestTest`
Expected: FAIL — `principals()`, `principal()`, `withPrincipals()` don't exist.

- [ ] **Step 3: Modify `Request.java`**

Add a `Map<String, Object> principals` field (final). The existing constructor gets a new parameter; add a delegating constructor that defaults principals to `Map.of()` so existing call sites compile unchanged.

```java
private final Map<String, Object> principals;

// existing public/package constructor — add the trailing principals parameter
public Request(
    byte[] body,
    Object parsedBody,
    com.retailsvc.http.JsonMapper mapper,
    String operationId,
    Map<String, String> pathParameters,
    String rawQuery,
    java.util.function.Function<String, String> firstHeader,
    Map<String, Object> principals) {
  // assign all fields as before
  this.principals = Map.copyOf(principals);
}

// keep the old constructor as a delegating overload
public Request(
    byte[] body,
    Object parsedBody,
    com.retailsvc.http.JsonMapper mapper,
    String operationId,
    Map<String, String> pathParameters,
    String rawQuery,
    java.util.function.Function<String, String> firstHeader) {
  this(body, parsedBody, mapper, operationId, pathParameters, rawQuery, firstHeader, Map.of());
}

public Map<String, Object> principals() {
  return principals;
}

public Optional<Object> principal(String schemeName) {
  return Optional.ofNullable(principals.get(schemeName));
}

public Request withPrincipals(Map<String, Object> principals) {
  return new Request(
      // pass every other field unchanged, plus the new principals
  );
}
```

(Replace inline FQNs with proper imports per the global memory rule.)

- [ ] **Step 4: Run all tests**

Run: `mvn test`
Expected: BUILD SUCCESS, existing tests unchanged, new ones pass.

- [ ] **Step 5: SonarLint scan + fix**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/Request.java \
        src/test/java/com/retailsvc/http/RequestTest.java
git commit -m "feat: Add Request.principals + withPrincipals immutable copy"
```

---

## Task 8: `SecurityFilter` — happy path (single allow)

**Files:**

- Create: `src/main/java/com/retailsvc/http/internal/SecurityFilter.java`
- Create: `src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.Credential;
import com.retailsvc.http.Request;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityFilterTest {

  @Test
  void allowsRequestWhenValidatorReturnsPrincipal() throws Exception {
    // Setup: one op "getX" requires bearerAuth; validator returns "user-1".
    Operation op =
        new Operation(
            "getX",
            com.retailsvc.http.spec.HttpMethod.GET,
            null,
            Optional.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            Optional.of(List.of(new SecurityRequirement(Map.of("bearerAuth", List.of())))));

    Map<String, SecurityScheme> schemes = Map.of("bearerAuth", new HttpBearer(Optional.empty()));
    Map<String, SchemeValidator> validators =
        Map.of("bearerAuth", (req, cred) -> Optional.of("user-1"));

    SecurityFilter filter =
        new SecurityFilter(
            Map.of("getX", op), schemes, List.of(), validators, /*externalAuth=*/ false);

    HttpExchange ex = mock(HttpExchange.class);
    Headers headers = new Headers();
    headers.add("Authorization", "Bearer token-xyz");
    when(ex.getRequestHeaders()).thenReturn(headers);
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/getX"));

    Request req = newMinimalRequest("getX");
    ScopedValueHarness.runWith(req, () -> {
          Chain chain = mock(Chain.class);
          filter.doFilter(ex, chain);
        });

    // Verify principals were rebound (the chain-mock implementation should
    // capture the ScopedValue at invocation time — see ScopedValueHarness).
    assertThat(ScopedValueHarness.lastSeenPrincipals()).containsEntry("bearerAuth", "user-1");
  }

  private static Request newMinimalRequest(String operationId) {
    return new Request(new byte[0], null, null, operationId, Map.of(), null, h -> null);
  }
}
```

(`ScopedValueHarness` is a test helper described in step 4.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SecurityFilterTest`
Expected: FAIL — `SecurityFilter` doesn't exist.

- [ ] **Step 3: Create the test helper**

`src/test/java/com/retailsvc/http/internal/ScopedValueHarness.java` (package-private):

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.Request;
import java.util.Map;

final class ScopedValueHarness {
  private static Map<String, Object> lastSeenPrincipals = Map.of();

  static void runWith(Request seed, ThrowingRunnable r) throws Exception {
    ScopedValue.where(DispatchHandler.CURRENT, seed)
        .call(
            () -> {
              try {
                r.run();
              } finally {
                lastSeenPrincipals = DispatchHandler.CURRENT.get().principals();
              }
              return null;
            });
  }

  static Map<String, Object> lastSeenPrincipals() {
    return lastSeenPrincipals;
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }
}
```

- [ ] **Step 4: Create `SecurityFilter.java` — happy path only**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.Credential;
import com.retailsvc.http.Request;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SecurityFilter extends Filter {

  private final Map<String, Operation> operationsById;
  private final Map<String, SecurityScheme> schemes;
  private final List<SecurityRequirement> rootSecurity;
  private final Map<String, SchemeValidator> validators;
  private final boolean externalAuth;

  public SecurityFilter(
      Map<String, Operation> operationsById,
      Map<String, SecurityScheme> schemes,
      List<SecurityRequirement> rootSecurity,
      Map<String, SchemeValidator> validators,
      boolean externalAuth) {
    this.operationsById = Map.copyOf(operationsById);
    this.schemes = Map.copyOf(schemes);
    this.rootSecurity = List.copyOf(rootSecurity);
    this.validators = Map.copyOf(validators);
    this.externalAuth = externalAuth;
  }

  @Override
  public String description() {
    return "Security";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    if (externalAuth) {
      chain.doFilter(exchange);
      return;
    }

    Request request = DispatchHandler.CURRENT.get();
    Operation op = operationsById.get(request.operationId());
    List<SecurityRequirement> effective = op.security().orElse(rootSecurity);

    if (effective.isEmpty()) {
      chain.doFilter(exchange);
      return;
    }

    for (SecurityRequirement group : effective) {
      Map<String, Object> principals = trySatisfy(group, exchange, request);
      if (principals != null) {
        try {
          ScopedValue.where(DispatchHandler.CURRENT, request.withPrincipals(principals))
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
        return;
      }
    }

    // No group satisfied. Task 9 will replace this with proper rejection.
    throw new UnsupportedOperationException("rejection path not implemented yet");
  }

  /** Returns the principals map on success, null if this group cannot be satisfied. */
  private Map<String, Object> trySatisfy(
      SecurityRequirement group, HttpExchange exchange, Request request) {
    Map<String, Object> principals = new LinkedHashMap<>();
    for (var entry : group.schemes().entrySet()) {
      String schemeName = entry.getKey();
      SecurityScheme scheme = schemes.get(schemeName);
      ExtractionResult result = CredentialExtractor.extract(scheme, exchange);
      if (result.kind() != ExtractionResult.Kind.FOUND) {
        return null;
      }
      Optional<Object> principal =
          validators.get(schemeName).validate(request, result.credential());
      if (principal.isEmpty()) {
        return null;
      }
      principals.put(schemeName, principal.get());
    }
    return Map.copyOf(principals);
  }
}
```

- [ ] **Step 5: Run the happy-path test**

Run: `mvn test -Dtest=SecurityFilterTest#allowsRequestWhenValidatorReturnsPrincipal`
Expected: PASS.

- [ ] **Step 6: SonarLint scan + fix**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/SecurityFilter.java \
        src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java \
        src/test/java/com/retailsvc/http/internal/ScopedValueHarness.java
git commit -m "feat(internal): SecurityFilter happy path (single-scheme allow)"
```

---

## Task 9: `SecurityFilter` — rejection rendering (401 / 403)

**Files:**

- Modify: `src/main/java/com/retailsvc/http/internal/SecurityFilter.java`
- Modify: `src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java`

- [ ] **Step 1: Write failing tests**

Append to `SecurityFilterTest.java`:

```java
@Test
void missingCredentialReturns401WithWwwAuthenticate() throws Exception {
  Operation op = opRequiring("bearerAuth");
  SecurityFilter filter =
      new SecurityFilter(
          Map.of("getX", op),
          Map.of("bearerAuth", new HttpBearer(Optional.empty())),
          List.of(),
          Map.of("bearerAuth", (req, cred) -> Optional.of("never-called")),
          false);

  HttpExchange ex = exchangeNoAuth("getX");
  java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
  when(ex.getResponseBody()).thenReturn(body);

  ScopedValueHarness.runWith(newMinimalRequest("getX"), () -> filter.doFilter(ex, mock(Chain.class)));

  verify(ex).sendResponseHeaders(eq(401), anyLong());
  assertThat(ex.getResponseHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer realm=\"api\"");
  assertThat(body.toString()).contains("\"status\":401").contains("credential missing");
}

@Test
void deniedValidatorReturns403() throws Exception {
  Operation op = opRequiring("bearerAuth");
  SecurityFilter filter =
      new SecurityFilter(
          Map.of("getX", op),
          Map.of("bearerAuth", new HttpBearer(Optional.empty())),
          List.of(),
          Map.of("bearerAuth", (req, cred) -> Optional.empty()), // deny
          false);

  HttpExchange ex = exchangeWithBearer("getX", "Bearer t");
  ScopedValueHarness.runWith(newMinimalRequest("getX"), () -> filter.doFilter(ex, mock(Chain.class)));

  verify(ex).sendResponseHeaders(eq(403), anyLong());
  assertThat(ex.getResponseHeaders().get("WWW-Authenticate")).isNull();
}
```

(Provide `opRequiring`, `exchangeNoAuth`, `exchangeWithBearer` as small private helpers — modeled on `exchangeWithHeader` from `CredentialExtractorTest`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=SecurityFilterTest`
Expected: FAIL — current rejection path throws `UnsupportedOperationException`.

- [ ] **Step 3: Replace the rejection path in `SecurityFilter.java`**

Replace `throw new UnsupportedOperationException(...)` with the logic that tracks the worst failure across all attempted groups and renders the response. Add a small inner record `Outcome` to track per-group results:

```java
private enum FailureKind {
  MISSING,
  MALFORMED,
  DENIED
}

private record Outcome(FailureKind worst, String schemeName, String detail) {}

// at the end of doFilter, after the for-loop, compute the final Outcome and call:
renderRejection(exchange, schemes, finalOutcome);
```

Implementation sketch (full body):

```java
List<Outcome> outcomes = new java.util.ArrayList<>();
for (SecurityRequirement group : effective) {
  Outcome attempted = tryGroup(group, exchange, request, /*principalsOut=*/ null);
  if (attempted == null) {
    // satisfied — already handled above via trySatisfy
  } else {
    outcomes.add(attempted);
  }
}

Outcome worst =
    outcomes.stream()
        .max(java.util.Comparator.comparing(o -> o.worst()))
        .orElseThrow();
renderRejection(exchange, worst);
```

Refactor `trySatisfy` so it returns either the principals map (allow) OR an `Outcome` (deny/missing/malformed). Two ways:

- Return a sealed result type. Cleanest.
- Use two methods: `trySatisfy` (allow path only, returns map or null) and `diagnoseGroup` (failure path, called only if all groups failed). Slightly redundant but easier to follow.

Either is acceptable; pick the sealed-result approach for readability:

```java
sealed interface GroupResult permits GroupResult.Allowed, GroupResult.Denied {
  record Allowed(Map<String, Object> principals) implements GroupResult {}
  record Denied(FailureKind kind, String schemeName, String detail) implements GroupResult {}
}
```

Render via the existing `ProblemDetailRenderer` (mirror what `RequestPreparationFilter` does for validation failures — same RFC 7807 envelope, just with `status=401` or `403`). Inspect `ProblemDetailRenderer.java` for its signature; if it doesn't already support arbitrary status, extend it with an overload that accepts `(HttpExchange, int status, String title, String detail)`.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=SecurityFilterTest`
Expected: all SecurityFilter tests pass.

- [ ] **Step 5: SonarLint scan + fix**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/SecurityFilter.java \
        src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java \
        src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java
git commit -m "feat(internal): SecurityFilter rejection rendering (401/403 + WWW-Authenticate)"
```

---

## Task 10: OR-of-AND group evaluation

**Files:**

- Modify: `src/main/java/com/retailsvc/http/internal/SecurityFilter.java` (the existing logic already iterates groups; this task is about exercising the branch)
- Modify: `src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java`

- [ ] **Step 1: Write failing tests**

Append:

```java
@Test
void andGroupAllSchemesMustSucceed() throws Exception {
  // Group requires both apiKeyAuth AND bearerAuth.
  Operation op =
      new Operation(
          "getX", com.retailsvc.http.spec.HttpMethod.GET, null,
          Optional.empty(), List.of(), Map.of(), Map.of(),
          Optional.of(
              List.of(
                  new SecurityRequirement(
                      Map.of("apiKeyAuth", List.of(), "bearerAuth", List.of())))));
  // ... configure both validators to allow; verify principals contains both keys.
}

@Test
void orFallbackTriesSecondGroupOnFirstFailure() throws Exception {
  // requirements = [{apiKeyAuth}, {bearerAuth}] — first group denied, second allowed.
}
```

- [ ] **Step 2: Run tests**

Most should pass already with current code (the loop iterates groups). If `andGroupAllSchemesMustSucceed` fails, fix.

- [ ] **Step 3: SonarLint scan + fix**

- [ ] **Step 4: Commit**

```bash
git commit -am "test(internal): Cover OR-of-AND evaluation in SecurityFilter"
```

---

## Task 11: Builder API — `securityValidator(...)`

**Files:**

- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` (Builder class — find it; on this codebase it is the inner `Builder` class inside `OpenApiServer.java`)
- Test: `src/test/java/com/retailsvc/http/SecurityBuilderTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityBuilderTest {

  @Test
  void securityValidatorRequiresNonNullName() {
    var builder = OpenApiServer.builder(/* a Spec — see existing tests for fixture loading */);
    assertThatThrownBy(() -> builder.securityValidator(null, (r, c) -> Optional.empty()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void securityValidatorRequiresNonNullValidator() {
    var builder = OpenApiServer.builder(/* Spec */);
    assertThatThrownBy(() -> builder.securityValidator("x", null))
        .isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SecurityBuilderTest`
Expected: FAIL — method doesn't exist.

- [ ] **Step 3: Add to `OpenApiServer.Builder`**

```java
private final java.util.Map<String, SchemeValidator> securityValidators = new java.util.HashMap<>();
private boolean externalAuth = false;

public Builder securityValidator(String schemeName, SchemeValidator validator) {
  java.util.Objects.requireNonNull(schemeName, "schemeName");
  java.util.Objects.requireNonNull(validator, "validator");
  securityValidators.put(schemeName, validator);
  return this;
}

public Builder useExternalAuthentication() {
  this.externalAuth = true;
  return this;
}
```

(Replace inline FQNs with proper imports.)

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=SecurityBuilderTest`
Expected: PASS.

- [ ] **Step 5: SonarLint scan + fix**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/test/java/com/retailsvc/http/SecurityBuilderTest.java
git commit -m "feat: Add securityValidator + useExternalAuthentication builder methods"
```

---

## Task 12: Wire `SecurityFilter` into the chain

**Files:**

- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

- [ ] **Step 1: Locate the chain assembly**

Read `OpenApiServer.java`. Find where filters are added to the `HttpContext` (look for `.getFilters().add(...)` or similar). The current order is `ExceptionFilter` → `RequestPreparationFilter` → `DispatchHandler`.

- [ ] **Step 2: Build the `Map<String, Operation>` index**

In the builder's `build()` method (just before the server is started), construct:

```java
Map<String, Operation> operationsById = spec.operations().stream()
    .collect(java.util.stream.Collectors.toUnmodifiableMap(
        Operation::operationId, op -> op));
```

- [ ] **Step 3: Insert the filter**

After the line that adds `RequestPreparationFilter`, add:

```java
context.getFilters().add(
    new SecurityFilter(
        operationsById,
        spec.securitySchemes(),
        spec.security(),
        Map.copyOf(securityValidators),
        externalAuth));
```

- [ ] **Step 4: Run the full test suite**

Run: `mvn verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: SonarLint scan + fix**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java
git commit -m "feat: Wire SecurityFilter into the request-processing chain"
```

---

## Task 13: Boot-time validator-registration check

**Files:**

- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` (Builder.build)
- Test: `src/test/java/com/retailsvc/http/SecurityBootValidationTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ...

class SecurityBootValidationTest {

  @Test
  void missingValidatorThrows() {
    // Load a Spec where operation "getX" requires bearerAuth.
    // builder() without .securityValidator("bearerAuth", ...) → .build() throws.
    assertThatThrownBy(() -> OpenApiServer.builder(spec).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no SchemeValidator registered for security scheme 'bearerAuth'");
  }

  @Test
  void unsupportedSchemeThrowsWhenReferenced() {
    // Spec with oauth2 scheme referenced from an operation → build() throws.
    assertThatThrownBy(() -> OpenApiServer.builder(specWithOauth2).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported type");
  }

  @Test
  void externalAuthSkipsValidatorCheck() {
    // Same spec as the missing-validator case, but with useExternalAuthentication() — must succeed.
    OpenApiServer server =
        OpenApiServer.builder(spec).useExternalAuthentication().build();
    assertThat(server).isNotNull();
    server.stop(0);
  }
}
```

(Use a small in-test `Spec` constructed from a `Map<String,Object>`, mirroring `SpecTest` style; do not introduce a new fixture file.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=SecurityBootValidationTest`
Expected: FAIL — no boot validation yet.

- [ ] **Step 3: Implement the check inside `Builder.build()`**

Before returning the built server:

```java
if (!externalAuth) {
  java.util.Set<String> referenced = new java.util.HashSet<>();
  for (Operation op : spec.operations()) {
    for (SecurityRequirement req : op.security().orElse(spec.security())) {
      referenced.addAll(req.schemes().keySet());
    }
  }
  for (String name : referenced) {
    SecurityScheme scheme = spec.securitySchemes().get(name);
    if (scheme == null) {
      throw new IllegalStateException(
          "security requirement references unknown scheme '" + name + "'");
    }
    if (scheme instanceof SecurityScheme.Unsupported u) {
      throw new IllegalStateException(
          "scheme '" + name + "' uses unsupported type '" + u.type() + "'");
    }
    if (!securityValidators.containsKey(name)) {
      throw new IllegalStateException(
          "no SchemeValidator registered for security scheme '" + name + "'");
    }
  }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=SecurityBootValidationTest`
Expected: PASS.

- [ ] **Step 5: SonarLint scan + fix**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/test/java/com/retailsvc/http/SecurityBootValidationTest.java
git commit -m "feat: Fail fast at boot if security validators are missing"
```

---

## Task 14: `useExternalAuthentication()` opt-out coverage

**Files:**

- Modify: `src/test/java/com/retailsvc/http/internal/SecurityFilterTest.java`

- [ ] **Step 1: Write failing test**

```java
@Test
void externalAuthBypassesEverything() throws Exception {
  Operation op = opRequiring("bearerAuth"); // root says required
  SecurityFilter filter =
      new SecurityFilter(
          Map.of("getX", op),
          Map.of("bearerAuth", new HttpBearer(Optional.empty())),
          List.of(),
          Map.of(), // no validators registered
          /*externalAuth=*/ true);

  HttpExchange ex = exchangeNoAuth("getX");
  Chain chain = mock(Chain.class);
  ScopedValueHarness.runWith(newMinimalRequest("getX"), () -> filter.doFilter(ex, chain));

  verify(chain).doFilter(ex);
  assertThat(ScopedValueHarness.lastSeenPrincipals()).isEmpty();
}
```

- [ ] **Step 2: Run — should pass already**

Run: `mvn test -Dtest=SecurityFilterTest#externalAuthBypassesEverything`
Expected: PASS (the short-circuit is already in the filter from Task 8). If FAIL, fix.

- [ ] **Step 3: Commit**

```bash
git commit -am "test(internal): Cover useExternalAuthentication bypass"
```

---

## Task 15: Acceptance fixture — `/api/v1/secure/*` operations

**Files:**

- Modify: `src/test/resources/openapi.json`
- Modify: `src/test/resources/openapi.yaml`

- [ ] **Step 1: Add secured operations to `openapi.json`**

Under `"paths"`, add four entries — all under the `/api/v1/secure/...` prefix so they don't collide with the operations exercised by `acceptance/k6/script.js`:

```jsonc
"/api/v1/secure/api-key": {
  "get": {
    "operationId": "secureApiKey",
    "security": [{"apiKeyAuth": []}],
    "responses": {"200": {"description": "ok"}}
  }
},
"/api/v1/secure/bearer": {
  "get": {
    "operationId": "secureBearer",
    "security": [{"bearerAuth": []}],
    "responses": {"200": {"description": "ok"}}
  }
},
"/api/v1/secure/basic": {
  "get": {
    "operationId": "secureBasic",
    "security": [{"basicAuth": []}],
    "responses": {"200": {"description": "ok"}}
  }
},
"/api/v1/secure/open": {
  "get": {
    "operationId": "secureOpen",
    "security": [],
    "responses": {"200": {"description": "ok"}}
  }
}
```

Under `"components"`, add:

```jsonc
"securitySchemes": {
  "apiKeyAuth": {"type": "apiKey", "name": "X-API-Key", "in": "header"},
  "bearerAuth": {"type": "http", "scheme": "bearer"},
  "basicAuth":  {"type": "http", "scheme": "basic"}
}
```

**Do not** add a top-level `"security"` block. Existing operations (`/api/v1/data` etc.) must continue to require no auth so the k6 script stays green.

- [ ] **Step 2: Mirror the same additions in `openapi.yaml`**

Per the global memory rule "openapi.yaml mirrors openapi.json", apply identical changes to the YAML fixture.

- [ ] **Step 3: Run the full test suite**

Run: `mvn verify`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run k6 smoke locally**

Boot the example server (in one terminal):

```bash
mvn test-compile exec:java \
    -Dexec.mainClass=com.retailsvc.http.start.ServerLauncher \
    -Dexec.classpathScope=test
```

Run k6 (in another terminal — k6 must be installed; if it isn't, fall back to the curl smoke described in `CLAUDE.md`):

```bash
k6 run acceptance/k6/script.js
```

Expected: every check passes (no 401s on the existing endpoints). If any check fails, the new operations leaked auth requirements onto an existing route — fix the JSON/YAML before continuing.

- [ ] **Step 5: Commit**

```bash
git add src/test/resources/openapi.json src/test/resources/openapi.yaml
git commit -m "test: Add /api/v1/secure/* operations and securitySchemes fixture"
```

---

## Task 16: `SecurityIT` integration test

**Files:**

- Create: `src/test/java/com/retailsvc/http/SecurityIT.java`

- [ ] **Step 1: Write the integration test**

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SecurityIT {

  private OpenApiServer server;

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  @Test
  void apiKeyAllowedWithCorrectHeader() throws Exception {
    Spec spec = Spec.fromPath(java.nio.file.Path.of("src/test/resources/openapi.json"));
    server =
        OpenApiServer.builder(spec)
            .securityValidator(
                "apiKeyAuth",
                (req, cred) ->
                    cred instanceof Credential.ApiKeyCredential ak && "good".equals(ak.value())
                        ? Optional.of("ok")
                        : Optional.empty())
            // register the other two as deny-all so they don't trip boot validation
            .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
            .securityValidator("basicAuth", (req, cred) -> Optional.empty())
            .handler("secureApiKey", req -> Response.json(200, "{}"))
            .handler("secureBearer", req -> Response.json(200, "{}"))
            .handler("secureBasic", req -> Response.json(200, "{}"))
            .handler("secureOpen", req -> Response.json(200, "{}"))
            // also register handlers for the existing operations in openapi.json so the server boots
            // ... (mirror what existing IT tests do — they likely have a helper for this)
            .port(0)
            .build();

    HttpResponse<String> ok = call(server.port(), "/api/v1/secure/api-key", "X-API-Key", "good");
    assertThat(ok.statusCode()).isEqualTo(200);

    HttpResponse<String> missing = call(server.port(), "/api/v1/secure/api-key", null, null);
    assertThat(missing.statusCode()).isEqualTo(401);

    HttpResponse<String> denied = call(server.port(), "/api/v1/secure/api-key", "X-API-Key", "bad");
    assertThat(denied.statusCode()).isEqualTo(403);
  }

  @Test
  void externalAuthBypassesEverything() throws Exception {
    Spec spec = Spec.fromPath(java.nio.file.Path.of("src/test/resources/openapi.json"));
    server =
        OpenApiServer.builder(spec)
            .useExternalAuthentication()
            .handler("secureApiKey", req -> Response.json(200, "{}"))
            // ... handlers for the rest
            .port(0)
            .build();

    HttpResponse<String> r = call(server.port(), "/api/v1/secure/api-key", null, null);
    assertThat(r.statusCode()).isEqualTo(200);
  }

  private HttpResponse<String> call(int port, String path, String headerName, String headerValue)
      throws Exception {
    var req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET();
    if (headerName != null) req.header(headerName, headerValue);
    return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
  }
}
```

**Note:** match the existing IT-class scaffolding (port binding, server lifecycle, handler registration helpers) by reading one of the existing `*IT.java` files (e.g. `OpenApiServerIT.java`). If that file has a shared `OpenApiServerHarness` or similar, reuse it instead of duplicating setup.

- [ ] **Step 2: Run integration tests**

Run: `mvn verify`
Expected: BUILD SUCCESS, `SecurityIT` runs under Failsafe.

- [ ] **Step 3: SonarLint scan + fix**

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/retailsvc/http/SecurityIT.java
git commit -m "test: SecurityIT — end-to-end 200/401/403 + external-auth bypass"
```

---

## Final verification

- [ ] **Step 1: Full build and test suite**

Run: `mvn verify`
Expected: BUILD SUCCESS, all tests pass (Surefire + Failsafe).

- [ ] **Step 2: k6 smoke**

Boot `ServerLauncher`, run `k6 run acceptance/k6/script.js`. Every check must pass.

- [ ] **Step 3: SonarLint final pass**

Run `mcp__sonarlint__sonar_analyze_staged` (or `_file` on each modified file). Fix any remaining issues. Confirm 0 new issues introduced by the branch.

- [ ] **Step 4: Push and open PR**

```bash
git push -u origin feat/security-schemes-design
```

Then open the PR via the GitHub URL printed by `git push` (the user opens PRs manually in this repo — gh CLI is not configured for PR creation).

---

## Self-review notes (for the executor)

- Every task ends with a SonarLint scan + commit. The branch must never push code with new Sonar issues (per `~/.claude/memory/feedback_sonar_pre_push.md`).
- Never pass `-c commit.gpgsign=false` or `--no-gpg-sign` (per `~/.claude/memory/feedback_never_bypass_signing.md`). Default commit signing must apply.
- Commit subjects use sentence-case (`fix(test): Hoist...`, not `fix(test): hoist...`) to satisfy commitlint.
- Test method names use camelCase (no underscores). Bodies use static imports for AssertJ/Mockito/JUnit and curly braces for every conditional.
- The k6 compatibility constraint (Task 15) is the most fragile assumption — running k6 (or the curl smoke from `CLAUDE.md`) is non-optional before pushing.
