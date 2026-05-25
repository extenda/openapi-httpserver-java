# CORS Preflight Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Handlers.corsPreflightHandler(...)` (two overloads — `List<String>` of allowed origins, and `Predicate<String>`) that answers CORS preflight `OPTIONS` requests with correct CORS response headers and `204 No Content`, validating origin / requested method / requested headers against caller-supplied allowlists.

**Architecture:** Adds two public static factories on the existing `Handlers` class plus one private static helper that builds the actual `RequestHandler`. No new public types. No changes to `OpenApiServer` — callers wire the handler with the existing `extraRoute("/path/*", ...)`. The list overload delegates to the predicate overload.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito (not needed here). `java.net.HttpURLConnection` constants for status codes. `java.time.Duration` for max-age. Existing `Request` / `Response` / `BadRequestException` types.

**Reference spec:** [docs/superpowers/specs/2026-05-25-cors-preflight-handler-design.md](../specs/2026-05-25-cors-preflight-handler-design.md)

---

## File Structure

- **Modify:** `src/main/java/com/retailsvc/http/Handlers.java` — add two `corsPreflightHandler(...)` overloads and a private static helper that assembles the `RequestHandler`.
- **Create:** `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java` — dedicated test class (the existing `HandlersTest.java` covers alive/resource handlers; `HealthHandlerTest.java` is a separate file — follow that precedent for the new handler).
- **Modify:** `README.md` — add a short subsection under the "Built-in handlers" area showing the wiring example.

---

## Conventions enforced for every code/test step

These are non-negotiable per project memory — apply in every step where you write code:

- Use `HttpURLConnection.HTTP_*` constants, never magic numbers (e.g. `HTTP_NO_CONTENT`, `HTTP_BAD_REQUEST`, `HTTP_FORBIDDEN`, `HTTP_BAD_METHOD`).
- Test method names are camelCase only (never underscore-separated).
- Static-import the test DSLs: `org.assertj.core.api.Assertions.assertThat`, `assertThatThrownBy`, `org.junit.jupiter.api.Assertions.*` if used, and the `HttpMethod` enum constants (`GET`, `POST`, `OPTIONS`, etc.).
- No inline fully-qualified type names; always add `import` statements.
- Always use curly braces around `if`/`else`/`for` bodies, even single-statement.
- Empty-body responses use `responseLength = -1`. In this code that means: rely on `Response.status(204)` / `Response.empty()` and never write a zero-length body.
- Code comments explain *intent* only — never mention Sonar / Javadoc / tooling.
- Don't call the project a "framework"; refer to it as "the library" / "the server".

---

## Tasks

### Task 1: Test the happy-path preflight returns 204 with all expected CORS headers

**Files:**
- Test: `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

- [ ] **Step 1: Create the test class skeleton with a happy-path test**

Create `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`:

```java
package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.DELETE;
import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static com.retailsvc.http.spec.HttpMethod.PUT;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.HttpMethod;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class CorsPreflightHandlerTest {

  private static final List<HttpMethod> METHODS = List.of(GET, POST, PUT, DELETE);
  private static final List<String> HEADERS = List.of("content-type", "authorization");
  private static final List<String> ORIGINS = List.of("https://app.example.com");

  private static Request preflight(String origin, String requestMethod, String requestHeaders) {
    UnaryOperator<String> lookup =
        name ->
            switch (name.toLowerCase(java.util.Locale.ROOT)) {
              case "origin" -> origin;
              case "access-control-request-method" -> requestMethod;
              case "access-control-request-headers" -> requestHeaders;
              default -> null;
            };
    return new Request(
        new byte[0], null, null, null, Map.of(), null, lookup, Map.of(), OPTIONS);
  }

  private static Request bare(HttpMethod method) {
    return new Request(
        new byte[0], null, null, null, Map.of(), null, n -> null, Map.of(), method);
  }

  @Test
  void corsPreflightHandlerReturns204WithExpectedHeadersOnValidPreflight() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(
            ORIGINS, METHODS, HEADERS, true, Duration.ofMinutes(10));

    Response resp =
        handler.handle(
            preflight("https://app.example.com", "POST", "content-type, authorization"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers())
        .containsEntry("Access-Control-Allow-Origin", "https://app.example.com")
        .containsEntry("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
        .containsEntry("Access-Control-Allow-Headers", "content-type, authorization")
        .containsEntry("Access-Control-Allow-Credentials", "true")
        .containsEntry("Access-Control-Max-Age", "600")
        .containsEntry("Vary", "Origin");
  }
}
```

- [ ] **Step 2: Run the test and verify it fails to compile**

Run: `mvn test -Dtest=CorsPreflightHandlerTest#corsPreflightHandlerReturns204WithExpectedHeadersOnValidPreflight`

Expected: compilation failure — `cannot find symbol: method corsPreflightHandler` on `Handlers`.

---

### Task 2: Implement `corsPreflightHandler` to make the happy-path test pass

**Files:**
- Modify: `src/main/java/com/retailsvc/http/Handlers.java`

- [ ] **Step 1: Add imports to `Handlers.java`**

Edit the import block at the top of `src/main/java/com/retailsvc/http/Handlers.java` to add:

```java
import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;

import com.retailsvc.http.spec.HttpMethod;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;  // already present, do not duplicate
```

(Merge with existing imports; do not duplicate `Collectors`. Keep imports alphabetised within their `import` / `import static` groups — Google Java Formatter enforces this on commit.)

- [ ] **Step 2: Add the two public factories and the private helper to `Handlers.java`**

Append the following members to the `Handlers` class (after `securityHeadersDecorator`, before `defaultExceptionHandler` to keep the "decorators / handlers / handlers" grouping):

```java
  /**
   * Returns a {@link RequestHandler} that answers CORS preflight {@code OPTIONS} requests for any
   * path the caller wires it under (typically via {@code OpenApiServer.builder().extraRoute("/api/*",
   * Handlers.corsPreflightHandler(...))}).
   *
   * <p>Requests are validated in order: origin against {@code allowedOrigins} (exact match),
   * {@code Access-Control-Request-Method} against {@code allowedMethods}, and each header in
   * {@code Access-Control-Request-Headers} against {@code allowedHeaders} (case-insensitive). A
   * non-{@code OPTIONS} request yields {@code 405} with {@code Allow: OPTIONS}; a missing
   * {@code Origin} or {@code Access-Control-Request-Method} header yields {@code 400}; any
   * disallowed origin / method / header yields {@code 403} with no CORS headers (the browser then
   * blocks the request).
   *
   * <p>On success the response is {@code 204 No Content} with {@code Access-Control-Allow-Origin}
   * echoing the request's {@code Origin}, the configured method and header allowlists, and
   * {@code Vary: Origin} so caches segment by origin. {@code Access-Control-Allow-Credentials} and
   * {@code Access-Control-Max-Age} are emitted only when enabled.
   *
   * @param allowedOrigins exact-match origin allowlist; never {@code null}
   * @param allowedMethods non-empty list of methods to advertise in {@code Allow-Methods}
   * @param allowedHeaders header allowlist (matched case-insensitively); may be empty (then
   *     {@code Access-Control-Allow-Headers} is omitted)
   * @param allowCredentials whether to emit {@code Access-Control-Allow-Credentials: true}
   * @param maxAge {@code Access-Control-Max-Age} value; {@code null} omits the header
   */
  public static RequestHandler corsPreflightHandler(
      List<String> allowedOrigins,
      List<HttpMethod> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      Duration maxAge) {
    Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    Set<String> origins = Set.copyOf(allowedOrigins);
    return corsPreflightHandler(
        origins::contains, allowedMethods, allowedHeaders, allowCredentials, maxAge);
  }

  /**
   * Predicate-based overload of {@link #corsPreflightHandler(List, List, List, boolean, Duration)}
   * for callers that need dynamic origin policy (regex, suffix match, config lookup).
   */
  public static RequestHandler corsPreflightHandler(
      Predicate<String> originAllowed,
      List<HttpMethod> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      Duration maxAge) {
    Objects.requireNonNull(originAllowed, "originAllowed must not be null");
    Objects.requireNonNull(allowedMethods, "allowedMethods must not be null");
    Objects.requireNonNull(allowedHeaders, "allowedHeaders must not be null");
    if (allowedMethods.isEmpty()) {
      throw new IllegalArgumentException("allowedMethods must not be empty");
    }
    if (maxAge != null && (maxAge.isNegative() || maxAge.getSeconds() > Integer.MAX_VALUE)) {
      throw new IllegalArgumentException(
          "maxAge must be non-negative and fit in an int number of seconds, got " + maxAge);
    }

    String allowMethodsHeader =
        allowedMethods.stream().map(Enum::name).collect(Collectors.joining(", "));
    String allowHeadersHeader = String.join(", ", allowedHeaders);
    Set<String> headerAllowlistLower =
        allowedHeaders.stream()
            .map(h -> h.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    String maxAgeHeader = maxAge == null ? null : Long.toString(maxAge.getSeconds());

    return req -> {
      if (req.method() != OPTIONS) {
        return Response.status(HTTP_BAD_METHOD).withHeader("Allow", "OPTIONS");
      }
      String origin = req.header("Origin").orElse(null);
      if (origin == null) {
        throw new BadRequestException("CORS preflight is missing the Origin header");
      }
      String requestMethod = req.header("Access-Control-Request-Method").orElse(null);
      if (requestMethod == null) {
        throw new BadRequestException(
            "CORS preflight is missing the Access-Control-Request-Method header");
      }
      if (!originAllowed.test(origin)) {
        return Response.status(HTTP_FORBIDDEN);
      }
      HttpMethod parsedMethod;
      try {
        parsedMethod = HttpMethod.parse(requestMethod);
      } catch (IllegalArgumentException e) {
        return Response.status(HTTP_FORBIDDEN);
      }
      if (!allowedMethods.contains(parsedMethod)) {
        return Response.status(HTTP_FORBIDDEN);
      }
      String requestedHeaders = req.header("Access-Control-Request-Headers").orElse("");
      for (String raw : requestedHeaders.split(",")) {
        String h = raw.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) {
          continue;
        }
        if (!headerAllowlistLower.contains(h)) {
          return Response.status(HTTP_FORBIDDEN);
        }
      }

      Response resp =
          Response.status(HTTP_NO_CONTENT)
              .withHeader("Access-Control-Allow-Origin", origin)
              .withHeader("Access-Control-Allow-Methods", allowMethodsHeader)
              .withHeader("Vary", "Origin");
      if (!allowedHeaders.isEmpty()) {
        resp = resp.withHeader("Access-Control-Allow-Headers", allowHeadersHeader);
      }
      if (allowCredentials) {
        resp = resp.withHeader("Access-Control-Allow-Credentials", "true");
      }
      if (maxAgeHeader != null) {
        resp = resp.withHeader("Access-Control-Max-Age", maxAgeHeader);
      }
      return resp;
    };
  }
```

Notes for the implementer:
- `Response.status(int)` and `Response.withHeader(String, String)` already exist; do not modify `Response.java`.
- `BadRequestException(String)` is the existing 400 ctor; do not pass a status.
- `HTTP_BAD_METHOD` is already imported at the top of `Handlers.java`.

- [ ] **Step 3: Run the happy-path test and verify it passes**

Run: `mvn test -Dtest=CorsPreflightHandlerTest#corsPreflightHandlerReturns204WithExpectedHeadersOnValidPreflight`

Expected: PASS, 1 test.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/retailsvc/http/Handlers.java \
        src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java
git commit -m "feat: Add CORS preflight handler"
```

---

### Task 3: Test header-omission cases (no credentials, no max-age, no allow-headers)

**Files:**
- Modify: `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

- [ ] **Step 1: Add three tests verifying optional headers are omitted when configured off**

Append inside the test class:

```java
  @Test
  void corsPreflightHandlerOmitsAllowCredentialsWhenFalse() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, Duration.ofMinutes(10));

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Credentials");
  }

  @Test
  void corsPreflightHandlerOmitsMaxAgeWhenNull() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, true, null);

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Max-Age");
  }

  @Test
  void corsPreflightHandlerEmitsMaxAgeInSecondsWhenSet() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, Duration.ofSeconds(75));

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).containsEntry("Access-Control-Max-Age", "75");
  }

  @Test
  void corsPreflightHandlerOmitsAllowHeadersWhenListEmpty() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, List.of(), false, null);

    Response resp = handler.handle(preflight("https://app.example.com", "POST", ""));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Headers");
    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
  }
```

- [ ] **Step 2: Run the new tests and verify all pass**

Run: `mvn test -Dtest=CorsPreflightHandlerTest`

Expected: PASS, 5 tests.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java
git commit -m "test: Cover CORS preflight optional header omission"
```

---

### Task 4: Test rejection paths (405 on non-OPTIONS, 400 on missing Origin / request-method, 403 on disallowed origin / method / header)

**Files:**
- Modify: `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

- [ ] **Step 1: Add rejection tests**

Append inside the test class:

```java
  @Test
  void corsPreflightHandlerRejectsNonOptionsWith405AndAllowOptions() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp = handler.handle(bare(GET));

    assertThat(resp.status()).isEqualTo(HTTP_BAD_METHOD);
    assertThat(resp.headers()).containsEntry("Allow", "OPTIONS");
  }

  @Test
  void corsPreflightHandlerRejectsMissingOriginWith400() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    assertThatThrownBy(() -> handler.handle(preflight(null, "POST", "content-type")))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Origin");
  }

  @Test
  void corsPreflightHandlerRejectsMissingRequestMethodWith400() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    assertThatThrownBy(
            () -> handler.handle(preflight("https://app.example.com", null, "content-type")))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Access-Control-Request-Method");
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedOriginWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp =
        handler.handle(preflight("https://evil.example.com", "POST", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Origin");
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedMethodWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, List.of(GET), HEADERS, false, null);

    Response resp =
        handler.handle(preflight("https://app.example.com", "DELETE", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedHeaderWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, List.of("content-type"), false, null);

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "x-secret"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerRejectsUnknownMethodTokenWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp =
        handler.handle(preflight("https://app.example.com", "BOGUS", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }
```

- [ ] **Step 2: Run the test class and verify all pass**

Run: `mvn test -Dtest=CorsPreflightHandlerTest`

Expected: PASS, 12 tests.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java
git commit -m "test: Cover CORS preflight rejection paths"
```

---

### Task 5: Test case-insensitive header matching, Vary header always present, list-overload delegation

**Files:**
- Modify: `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

- [ ] **Step 1: Add three tests**

Append:

```java
  @Test
  void corsPreflightHandlerMatchesHeadersCaseInsensitively() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(
            ORIGINS, METHODS, List.of("Content-Type", "Authorization"), false, null);

    Response resp =
        handler.handle(
            preflight("https://app.example.com", "POST", "CONTENT-TYPE, authorization"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
  }

  @Test
  void corsPreflightHandlerEchoesOriginAndIncludesVary() {
    Predicate<String> anyExampleOrigin = o -> o.endsWith(".example.com");
    RequestHandler handler =
        Handlers.corsPreflightHandler(anyExampleOrigin, METHODS, HEADERS, false, null);

    Response resp =
        handler.handle(preflight("https://tenant-7.example.com", "POST", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(resp.headers())
        .containsEntry("Access-Control-Allow-Origin", "https://tenant-7.example.com")
        .containsEntry("Vary", "Origin");
  }

  @Test
  void corsPreflightHandlerListOverloadDelegatesToPredicateBehaviour() {
    RequestHandler list =
        Handlers.corsPreflightHandler(
            List.of("https://a.example.com", "https://b.example.com"),
            METHODS,
            HEADERS,
            false,
            null);

    Response allowed = list.handle(preflight("https://b.example.com", "POST", "content-type"));
    Response denied = list.handle(preflight("https://c.example.com", "POST", "content-type"));

    assertThat(allowed.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(denied.status()).isEqualTo(HTTP_FORBIDDEN);
  }
```

- [ ] **Step 2: Run all tests in the class**

Run: `mvn test -Dtest=CorsPreflightHandlerTest`

Expected: PASS, 15 tests.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java
git commit -m "test: Cover CORS preflight case-insensitivity, Vary, list overload"
```

---

### Task 6: Test constructor-validation (nulls, empty methods, negative maxAge, maxAge overflow)

**Files:**
- Modify: `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

- [ ] **Step 1: Add validation tests**

Append:

```java
  @Test
  void corsPreflightHandlerRejectsNullOriginList() {
    assertThatThrownBy(
            () ->
                Handlers.corsPreflightHandler(
                    (List<String>) null, METHODS, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedOrigins");
  }

  @Test
  void corsPreflightHandlerRejectsNullOriginPredicate() {
    assertThatThrownBy(
            () ->
                Handlers.corsPreflightHandler(
                    (Predicate<String>) null, METHODS, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("originAllowed");
  }

  @Test
  void corsPreflightHandlerRejectsNullMethods() {
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, null, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedMethods");
  }

  @Test
  void corsPreflightHandlerRejectsEmptyMethods() {
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, List.of(), HEADERS, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedMethods");
  }

  @Test
  void corsPreflightHandlerRejectsNullHeaders() {
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, METHODS, null, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedHeaders");
  }

  @Test
  void corsPreflightHandlerRejectsNegativeMaxAge() {
    assertThatThrownBy(
            () ->
                Handlers.corsPreflightHandler(
                    ORIGINS, METHODS, HEADERS, false, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAge");
  }

  @Test
  void corsPreflightHandlerRejectsOverflowingMaxAge() {
    Duration tooBig = Duration.ofSeconds((long) Integer.MAX_VALUE + 1);
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, tooBig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAge");
  }
```

- [ ] **Step 2: Run all tests in the class**

Run: `mvn test -Dtest=CorsPreflightHandlerTest`

Expected: PASS, 22 tests.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java
git commit -m "test: Cover CORS preflight constructor validation"
```

---

### Task 7: Document the handler in README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Find the existing "Built-in handlers" / Handlers section**

Run: `grep -n "aliveHandler\|healthHandler\|securityHeadersDecorator" README.md`

Identify the surrounding section heading (probably `## Built-in handlers` or similar). The new subsection follows the same heading depth and prose style as the existing entries.

- [ ] **Step 2: Add a subsection after the existing handler entries**

Insert this subsection at the end of the Built-in handlers section (mirror the heading level used by `aliveHandler` and `healthHandler`):

```markdown
### CORS preflight handler

Answers `OPTIONS` preflight requests so browsers can perform cross-origin
calls against your service. The handler is preflight-only; wire it on a
wildcard `extraRoute` path that covers the routes you want to expose to
browsers.

```java
server = OpenApiServer.builder()
    .extraRoute("/api/*", Handlers.corsPreflightHandler(
        List.of("https://app.example.com"),
        List.of(GET, POST, PUT, DELETE),
        List.of("content-type", "authorization"),
        true,                     // allowCredentials
        Duration.ofMinutes(10)))  // Access-Control-Max-Age
    .handlers(operations)
    .build();
```

For dynamic origin policy (regex, tenant lookup), pass a
`Predicate<String>` instead of a `List<String>`.
```

- [ ] **Step 3: Verify the file still renders sensibly**

Run: `grep -n "corsPreflightHandler" README.md`

Expected: the new code block appears in exactly the spot you inserted it; no other lines reference the new handler.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: Document CORS preflight handler in README"
```

---

### Task 8: Run the full build and verify clean baseline

**Files:** (none)

- [ ] **Step 1: Run the full unit test suite**

Run: `mvn test`

Expected: BUILD SUCCESS; the `CorsPreflightHandlerTest` reports 22 tests, 0 failures; no other tests regressed.

- [ ] **Step 2: Run integration tests**

Run: `mvn verify`

Expected: BUILD SUCCESS; coverage report appears at `target/site/jacoco/`.

- [ ] **Step 3: Verify SonarLint MCP is clean on the touched files**

Run SonarLint MCP analysis (the analyse-files tool) on:
- `src/main/java/com/retailsvc/http/Handlers.java`
- `src/test/java/com/retailsvc/http/CorsPreflightHandlerTest.java`

Expected: zero new issues introduced by this branch.

Note: SonarLint MCP cannot see files inside `.claude/worktrees/` — its `/workspace` mount points at the main repo. If you hit `not_found` for the worktree paths, accept the CI Sonar scan as the source of truth for the branch.

- [ ] **Step 4: Final summary**

The branch `feat/cors-handler` now contains:
- Two new public factory overloads on `Handlers` plus a private helper.
- A new `CorsPreflightHandlerTest` with 22 tests covering happy path, optional-header omission, every rejection branch, case-insensitive header matching, list-overload delegation, and constructor validation.
- A README subsection wiring the handler via `extraRoute`.

Ready to open a PR against `master`.
