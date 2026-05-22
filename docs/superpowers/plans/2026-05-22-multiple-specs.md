# Multiple OpenAPI specs — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow `OpenApiServer` to serve more than one `Spec` on a single process, with per-spec scoping of `operationId`s and security-scheme names, while keeping the existing single-spec Builder API source-compatible.

**Architecture:** Each registered spec becomes a package-private `SpecBinding` record (spec + handlers + validators + derived `DefaultValidator` + `Router`). At runtime, the server creates one `HttpContext` per binding at the spec's `basePath`, with a filter chain wired against that binding's maps. JDK `HttpServer`'s longest-prefix-match handles routing across bindings. The legacy single-spec Builder methods stay and synthesise one implicit `addSpec()` bundle at `build()` time; mixing them with explicit `addSpec()` calls is rejected.

**Tech Stack:** Java 25, `com.sun.net.httpserver.HttpServer`, JUnit 5, AssertJ, Mockito. Maven build (`mvn test` / `mvn verify`).

**Spec reference:** `docs/superpowers/specs/2026-05-22-multiple-specs-design.md`.

---

## File structure

**New files:**

- `src/main/java/com/retailsvc/http/internal/SpecBinding.java` — package-private record bundling one spec's runtime state.
- `src/test/java/com/retailsvc/http/MultiSpecServerTest.java` — unit tests for the new multi-spec Builder behaviour.
- `src/test/java/com/retailsvc/http/MultiSpecServerIT.java` — integration test mounting two derived specs at different base paths under a real `HttpServer`.
- `src/test/java/com/retailsvc/http/support/SpecFixtures.java` — small test helper that reads `openapi.json`, deep-clones the parsed `Map`, mutates `servers[0].url`, and returns a `Spec`. Reused across the new tests; **no new OpenAPI fixture files**.

**Modified files:**

- `src/main/java/com/retailsvc/http/OpenApiServer.java` — constructor takes `List<SpecBinding>` instead of single `Spec`/handlers/validators; one `HttpContext` per binding; cross-binding basePath conflict check; Builder gets `addSpec()` plus synthesis logic.

No other production files need to change. Existing filters (`RequestPreparationFilter`, `SecurityFilter`, `DispatchHandler`, `ExceptionFilter`) keep their current constructors — we instantiate one per binding instead of one per server.

---

## Phased approach

The plan is split into two phases to keep risk small:

- **Phase 1 — Internal refactor (no public API change):** introduce `SpecBinding`, change `OpenApiServer`'s constructor to a `List<SpecBinding>` of size 1, keep the existing Builder surface untouched. All 440 existing tests must continue passing without modification.
- **Phase 2 — Multi-spec public API:** add `addSpec()` builder methods, synthesis-of-legacy-calls, cross-binding validation, new tests.

Each phase ends green (compile + tests pass) and is committable on its own.

---

## Phase 1 — Internal refactor

### Task 1: Introduce `SpecBinding` record

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/SpecBinding.java`

- [x] **Step 1: Write `SpecBinding`**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import java.util.Map;

/**
 * Bundles everything the server needs to serve one OpenAPI spec: the spec itself, the handler map
 * keyed by {@code operationId}, the security-scheme validator map, and the derived {@link Router}
 * and {@link DefaultValidator}. One {@code SpecBinding} drives exactly one {@code HttpContext}.
 */
public record SpecBinding(
    Spec spec,
    Map<String, RequestHandler> handlers,
    Map<String, SchemeValidator> securityValidators,
    DefaultValidator validator,
    Router router) {

  public SpecBinding {
    handlers = Map.copyOf(handlers);
    securityValidators = Map.copyOf(securityValidators);
  }

  /** Builds a binding by deriving the {@link Router} and {@link DefaultValidator} from the spec. */
  public static SpecBinding of(
      Spec spec,
      Map<String, RequestHandler> handlers,
      Map<String, SchemeValidator> securityValidators) {
    return new SpecBinding(
        spec,
        handlers,
        securityValidators,
        new DefaultValidator(spec::resolveSchema),
        new Router(spec.operations()));
  }
}
```

- [x] **Step 2: Verify compilation**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS (the record isn't used yet).

- [x] **Step 3: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/SpecBinding.java
git commit -m "refactor: Add SpecBinding record for per-spec server state"
```

---

### Task 2: Rewire `OpenApiServer` constructor around a single-element `List<SpecBinding>`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

The goal here is purely structural: the constructor stops taking a `Spec` and instead takes `List<SpecBinding>`. The Builder still produces a list of size 1. No public API change yet.

- [x] **Step 1: Update `OpenApiServer` constructor signature and body**

Replace the constructor (currently lines 73–157) with a version that loops over bindings:

```java
OpenApiServer(
    java.util.List<SpecBinding> bindings,
    Map<String, TypeMapper> bodyMappers,
    HandlerConfig handlerConfig,
    int port,
    InetAddress bindAddress,
    int shutdownTimeoutSeconds,
    SSLContext sslContext)
    throws IOException {

  requireNonNull(bindings, "bindings must not be null");
  if (bindings.isEmpty()) {
    throw new IllegalStateException("at least one spec binding is required");
  }
  requireNonNull(bodyMappers, "bodyMappers must not be null");
  ExceptionHandler exceptionHandler = handlerConfig.exceptionHandler();

  long t0 = System.currentTimeMillis();

  InetSocketAddress socketAddress =
      (bindAddress == null)
          ? new InetSocketAddress(port)
          : new InetSocketAddress(bindAddress, port);
  if (sslContext != null) {
    HttpsServer https = HttpsServer.create(socketAddress, 0);
    https.setHttpsConfigurator(new TlsHttpsConfigurator(sslContext));
    this.httpServer = https;
  } else {
    this.httpServer = HttpServer.create(socketAddress, 0);
  }
  httpServer.setExecutor(newThreadPerTaskExecutor(ofVirtual().name("http-", 0).factory()));

  ResponseRenderer renderer = new ResponseRenderer(bodyMappers);

  boolean anyBindingAtRoot = false;
  for (SpecBinding binding : bindings) {
    String basePath = Optional.ofNullable(binding.spec().basePath()).orElse("/");
    anyBindingAtRoot |= "/".equals(basePath);
    Map<String, Operation> operationsById =
        binding.spec().operations().stream()
            .collect(Collectors.toUnmodifiableMap(Operation::operationId, op -> op));
    HttpContext ctx = httpServer.createContext(basePath);
    ctx.getFilters()
        .add(
            new RequestPreparationFilter(
                binding.spec(),
                binding.router(),
                binding.validator(),
                bodyMappers,
                exceptionHandler,
                renderer,
                handlerConfig.afterHooks()));
    ctx.getFilters()
        .add(
            new SecurityFilter(
                operationsById,
                binding.spec().securitySchemes(),
                binding.spec().security(),
                binding.securityValidators(),
                handlerConfig.externalAuth()));
    ctx.setHandler(
        new DispatchHandler(
            binding.handlers(),
            handlerConfig.interceptors(),
            handlerConfig.decorators(),
            renderer));
  }

  for (Map.Entry<String, RequestHandler> e : handlerConfig.extras().entrySet()) {
    HttpContext extraCtx = httpServer.createContext(e.getKey());
    extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
    extraCtx.setHandler(new ExtraRouteAdapter(e.getValue(), renderer));
  }

  if (!anyBindingAtRoot) {
    httpServer.createContext("/", new NotFoundHandler());
  }
  httpServer.start();

  this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

  String host = httpServer.getAddress().getHostString();
  String displayHost = host.contains(":") ? "[" + host + "]" : host;
  LOG.info(
      "Server started ({}:{}) in {}ms",
      displayHost,
      httpServer.getAddress().getPort(),
      System.currentTimeMillis() - t0);
}
```

- [x] **Step 2: Drop the now-unused `handlers` field from `HandlerConfig`**

In `OpenApiServer.java`, change the `HandlerConfig` record to remove `handlers` (it's per-binding now):

```java
record HandlerConfig(
    List<RequestInterceptor> interceptors,
    List<ResponseDecorator> decorators,
    ExceptionHandler exceptionHandler,
    Map<String, RequestHandler> extras,
    boolean externalAuth,
    List<AfterResponseHook> afterHooks) {}
```

Also drop the per-binding `securityValidators` from `HandlerConfig` (they live on each `SpecBinding` now). Update every callsite of `HandlerConfig` accordingly.

- [x] **Step 3: Update `Builder.build()` to construct one binding and pass `List.of(binding)`**

In `Builder.build()` (currently lines 364–402), replace the construction of `HandlerConfig` and the call to `new OpenApiServer(...)` with:

```java
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
  if (!externalAuth) {
    validateSecurityWiring(spec, securityValidators);
  }
  validateHandlerWiring(spec, handlers);
  Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
  ExceptionHandler effectiveExceptionHandler =
      exceptionHandler != null ? exceptionHandler : Handlers.defaultExceptionHandler();
  HandlerConfig handlerConfig =
      new HandlerConfig(
          interceptors,
          decorators,
          effectiveExceptionHandler,
          extras,
          externalAuth,
          List.copyOf(afterHooks));
  SpecBinding binding = SpecBinding.of(spec, handlers, securityValidators);
  int resolvedPort = resolvePort();
  SSLContext sslContext =
      httpsCertChain != null ? PemSslContext.load(httpsCertChain, httpsPrivateKey) : null;
  return new OpenApiServer(
      java.util.List.of(binding),
      resolved,
      handlerConfig,
      resolvedPort,
      bindAddress,
      shutdownTimeoutSeconds,
      sslContext);
}
```

- [x] **Step 4: Run the full unit test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. All 440 existing tests pass with no edits. (If anything fails, it's a refactoring mistake — fix before moving on.)

- [x] **Step 5: Run the integration test suite**

Run: `mvn -q verify -DskipITs=false`
Expected: BUILD SUCCESS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java
git commit -m "refactor: Drive OpenApiServer from a list of SpecBinding"
```

---

## Phase 2 — Public multi-spec API

### Task 3: Test fixture helper for deriving specs without new resource files

**Files:**
- Create: `src/test/java/com/retailsvc/http/support/SpecFixtures.java`

This avoids adding any new `openapi*.json|yaml` files — per project memory, fixtures are minimised. The helper reads the existing `/openapi.json`, deep-clones the parsed `Map`, and lets a caller override `servers[0].url`.

- [x] **Step 1: Write the helper**

Created with proper imports (no inline FQNs per `feedback_no_inline_fqn.md`) and deep-clone logic.

- [x] **Step 2: Verify it compiles**

Ran: `mvn -q test-compile`
Result: BUILD SUCCESS. Compiled class at `target/test-classes/com/retailsvc/http/support/SpecFixtures.class`.

- [x] **Step 3: Commit**

```
git add src/test/java/com/retailsvc/http/support/SpecFixtures.java
git commit -m "test: Add SpecFixtures helper to derive multiple specs from one fixture"
```

Committed as: `abe2369` (Google Java Formatter reformatted javadoc wrapping; pre-commit hooks passed).

---

### Task 4: Failing test — `addSpec()` accepts multiple bindings with distinct base paths

**Files:**
- Create: `src/test/java/com/retailsvc/http/MultiSpecServerTest.java`

- [x] **Step 1: Write the failing test**

```java
package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.support.SpecFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiSpecServerTest {

  @Test
  void servesTwoBindingsOnDistinctBasePaths() throws Exception {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

    RequestHandler v1Handler = req -> Response.ok(Map.of("version", "v1"));
    RequestHandler v2Handler = req -> Response.ok(Map.of("version", "v2"));

    Map<String, RequestHandler> v1Handlers = handlersFor(v1, v1Handler);
    Map<String, RequestHandler> v2Handlers = handlersFor(v2, v2Handler);

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, v1Handlers)
            .addSpec(v2, v2Handlers)
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      assertThat(get("http://localhost:" + port + "/api/v1/ping").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get("http://localhost:" + port + "/api/v2/ping").statusCode()).isEqualTo(HTTP_OK);
    }
  }

  private static Map<String, RequestHandler> handlersFor(Spec spec, RequestHandler shared) {
    java.util.LinkedHashMap<String, RequestHandler> out = new java.util.LinkedHashMap<>();
    spec.operations().forEach(op -> out.put(op.operationId(), shared));
    return out;
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
```

Note: the test uses `useExternalAuthentication()` to side-step per-spec validator wiring — the security branch is covered separately in Task 8. It uses the existing `/ping` operation from `openapi.json` (which is GET-only and unsecured per the fixture). If the fixture's `/ping` requires auth, switch to the first GET-only unsecured operation in the spec — discoverable by inspecting `src/test/resources/openapi.json`.

- [x] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=MultiSpecServerTest#servesTwoBindingsOnDistinctBasePaths`
Expected: COMPILE FAILURE — `addSpec` does not yet exist on `Builder`.

- [x] **Step 3: Do NOT commit yet** — proceed to Task 5 to make it pass.

---

### Task 5: Add `addSpec()` builder methods

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

- [x] **Step 1: Add bindings list and `addSpec` methods to `Builder`**

Inside `Builder`, alongside the existing fields, add:

```java
private final java.util.List<SpecBinding> bindings = new java.util.ArrayList<>();

/**
 * Registers an OpenAPI {@link Spec} with the handlers and security validators that serve it. May
 * be called more than once; each binding becomes its own {@link com.sun.net.httpserver.HttpContext}
 * at the spec's {@code basePath}. {@code operationId}s and security-scheme names only need to be
 * unique within a single spec.
 */
public Builder addSpec(
    Spec spec,
    Map<String, RequestHandler> handlers,
    Map<String, SchemeValidator> securityValidators) {
  requireNonNull(spec, "spec must not be null");
  requireNonNull(handlers, "handlers must not be null");
  requireNonNull(securityValidators, "securityValidators must not be null");
  bindings.add(SpecBinding.of(spec, handlers, securityValidators));
  return this;
}

/** Convenience overload for specs that declare no security schemes. */
public Builder addSpec(Spec spec, Map<String, RequestHandler> handlers) {
  return addSpec(spec, handlers, java.util.Map.of());
}
```

- [x] **Step 2: Update `build()` to consume bindings**

Replace the `build()` method body so it either:
- uses the explicit `bindings` list when `addSpec()` was called, OR
- synthesises a single binding from the legacy `spec`/`handlers`/`securityValidators` fields.

Mixing both forms is rejected. Full replacement of `build()`:

```java
public OpenApiServer build() throws IOException {
  boolean usedLegacy = spec != null || handlers != null;
  boolean usedAddSpec = !bindings.isEmpty();
  if (usedLegacy && usedAddSpec) {
    throw new IllegalStateException(
        "use either spec()/handler()/securityValidator() or addSpec(), not both");
  }
  java.util.List<SpecBinding> effectiveBindings;
  if (usedAddSpec) {
    effectiveBindings = java.util.List.copyOf(bindings);
  } else {
    requireNonNull(spec, "Spec must not be null");
    requireNonNull(handlers, "handlers must not be null");
    effectiveBindings = java.util.List.of(SpecBinding.of(spec, handlers, securityValidators));
  }

  // Per-binding wiring validation.
  for (SpecBinding b : effectiveBindings) {
    if (!externalAuth) {
      validateSecurityWiring(b.spec(), b.securityValidators());
    }
    validateHandlerWiring(b.spec(), b.handlers());
  }

  // Cross-binding: duplicate basePath rejection.
  java.util.Map<String, String> seenBasePaths = new java.util.LinkedHashMap<>();
  for (SpecBinding b : effectiveBindings) {
    String bp = Optional.ofNullable(b.spec().basePath()).orElse("/");
    String existingTitle = seenBasePaths.putIfAbsent(bp, b.spec().info().title());
    if (existingTitle != null) {
      throw new IllegalStateException(
          "duplicate basePath '"
              + bp
              + "' across specs: '"
              + existingTitle
              + "' and '"
              + b.spec().info().title()
              + "'");
    }
  }

  // Extras must not collide with any binding's basePath.
  for (String path : extras.keySet()) {
    if (seenBasePaths.containsKey(path)) {
      throw new IllegalStateException(
          "extra handler path " + path + " conflicts with spec basePath " + path);
    }
  }

  Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
  ExceptionHandler effectiveExceptionHandler =
      exceptionHandler != null ? exceptionHandler : Handlers.defaultExceptionHandler();
  HandlerConfig handlerConfig =
      new HandlerConfig(
          interceptors,
          decorators,
          effectiveExceptionHandler,
          extras,
          externalAuth,
          List.copyOf(afterHooks));
  int resolvedPort = resolvePort();
  SSLContext sslContext =
      httpsCertChain != null ? PemSslContext.load(httpsCertChain, httpsPrivateKey) : null;
  return new OpenApiServer(
      effectiveBindings,
      resolved,
      handlerConfig,
      resolvedPort,
      bindAddress,
      shutdownTimeoutSeconds,
      sslContext);
}
```

- [x] **Step 3: Run the new test**

Run: `mvn -q test -Dtest=MultiSpecServerTest#servesTwoBindingsOnDistinctBasePaths`
Expected: PASS.

- [x] **Step 4: Run the full suite to confirm nothing regressed**

Run: `mvn -q test`
Expected: BUILD SUCCESS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/test/java/com/retailsvc/http/MultiSpecServerTest.java
git commit -m "feat: Add addSpec() builder method for multi-spec servers"
```

---

### Task 6: Test — identical `operationId`s across bindings dispatch to their own handler

**Files:**
- Modify: `src/test/java/com/retailsvc/http/MultiSpecServerTest.java`

- [x] **Step 1: Add the test**

```java
@Test
void identicalOperationIdsAcrossBindingsDispatchIndependently() throws Exception {
  Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
  Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

  java.util.concurrent.atomic.AtomicInteger v1Hits = new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicInteger v2Hits = new java.util.concurrent.atomic.AtomicInteger();

  Map<String, RequestHandler> v1Handlers =
      handlersFor(v1, req -> { v1Hits.incrementAndGet(); return Response.ok(Map.of("v", 1)); });
  Map<String, RequestHandler> v2Handlers =
      handlersFor(v2, req -> { v2Hits.incrementAndGet(); return Response.ok(Map.of("v", 2)); });

  try (OpenApiServer server =
      OpenApiServer.builder()
          .port(0)
          .addSpec(v1, v1Handlers)
          .addSpec(v2, v2Handlers)
          .useExternalAuthentication()
          .build()) {

    int port = server.listenPort();
    get("http://localhost:" + port + "/api/v1/ping");
    get("http://localhost:" + port + "/api/v2/ping");

    assertThat(v1Hits.get()).isEqualTo(1);
    assertThat(v2Hits.get()).isEqualTo(1);
  }
}
```

- [x] **Step 2: Run it**

Run: `mvn -q test -Dtest=MultiSpecServerTest#identicalOperationIdsAcrossBindingsDispatchIndependently`
Expected: PASS (no production change needed — `Map<String, RequestHandler>` per binding already gives us this).

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/MultiSpecServerTest.java
git commit -m "test: Verify identical operationIds across bindings dispatch independently"
```

---

### Task 7: Test — duplicate basePath rejected at build

**Files:**
- Modify: `src/test/java/com/retailsvc/http/MultiSpecServerTest.java`

- [x] **Step 1: Add the test**

```java
@Test
void rejectsTwoBindingsWithSameBasePath() {
  Spec a = SpecFixtures.specAt("http://localhost/api/v1");
  Spec b = SpecFixtures.specAt("http://localhost/api/v1");
  Map<String, RequestHandler> handlersA = handlersFor(a, req -> Response.ok(Map.of()));
  Map<String, RequestHandler> handlersB = handlersFor(b, req -> Response.ok(Map.of()));

  assertThatThrownBy(
          () ->
              OpenApiServer.builder()
                  .port(0)
                  .addSpec(a, handlersA)
                  .addSpec(b, handlersB)
                  .useExternalAuthentication()
                  .build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("duplicate basePath")
      .hasMessageContaining("/api/v1");
}
```

- [x] **Step 2: Run it**

Run: `mvn -q test -Dtest=MultiSpecServerTest#rejectsTwoBindingsWithSameBasePath`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/MultiSpecServerTest.java
git commit -m "test: Reject two bindings on the same basePath"
```

---

### Task 8: Test — per-binding security wiring is enforced independently

**Files:**
- Modify: `src/test/java/com/retailsvc/http/MultiSpecServerTest.java`

- [x] **Step 1: Add the test**

This test confirms that when a spec references a security scheme, the corresponding `SchemeValidator` must be supplied in *that binding's* validator map — not somewhere else. It uses the existing fixture which (per repo convention) declares at least one secured operation.

```java
@Test
void missingValidatorOnOneBindingFailsIndependently() {
  // Only run if the test spec has any security requirements; otherwise this assertion is vacuous.
  Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
  boolean specHasSecurity = !v1.securitySchemes().isEmpty();
  org.junit.jupiter.api.Assumptions.assumeTrue(
      specHasSecurity, "test spec has no security schemes to exercise");

  Map<String, RequestHandler> handlers = handlersFor(v1, req -> Response.ok(Map.of()));

  assertThatThrownBy(
          () ->
              OpenApiServer.builder()
                  .port(0)
                  .addSpec(v1, handlers, Map.of()) // empty validators on purpose
                  .build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("no SchemeValidator registered for security scheme");
}
```

- [x] **Step 2: Run it**

Run: `mvn -q test -Dtest=MultiSpecServerTest#missingValidatorOnOneBindingFailsIndependently`
Expected: PASS (the assumption skips if the fixture is unsecured; otherwise the wiring check fires).

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/MultiSpecServerTest.java
git commit -m "test: Per-binding security validator wiring is enforced"
```

---

### Task 9: Test — mixing legacy methods with `addSpec()` is rejected

**Files:**
- Modify: `src/test/java/com/retailsvc/http/MultiSpecServerTest.java`

- [x] **Step 1: Add the test**

```java
@Test
void rejectsMixingLegacySpecMethodsWithAddSpec() {
  Spec a = SpecFixtures.specAt("http://localhost/api/v1");
  Spec b = SpecFixtures.specAt("http://localhost/api/v2");
  Map<String, RequestHandler> handlersA = handlersFor(a, req -> Response.ok(Map.of()));
  Map<String, RequestHandler> handlersB = handlersFor(b, req -> Response.ok(Map.of()));

  assertThatThrownBy(
          () ->
              OpenApiServer.builder()
                  .port(0)
                  .spec(a)
                  .handlers(handlersA)
                  .addSpec(b, handlersB)
                  .useExternalAuthentication()
                  .build())
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("use either spec()/handler()/securityValidator() or addSpec()");
}
```

- [x] **Step 2: Run it**

Run: `mvn -q test -Dtest=MultiSpecServerTest#rejectsMixingLegacySpecMethodsWithAddSpec`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/MultiSpecServerTest.java
git commit -m "test: Reject mixing legacy spec() with addSpec()"
```

---

### Task 10: Integration test — two bindings under a real `HttpServer`

**Files:**
- Create: `src/test/java/com/retailsvc/http/MultiSpecServerIT.java`

This runs under `mvn verify` (Failsafe) and exercises real HTTP traffic.

- [x] **Step 1: Write the IT**

```java
package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.support.SpecFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiSpecServerIT {

  @Test
  void twoBindingsServeTrafficAndUnknownPathReturns404() throws Exception {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

    Map<String, RequestHandler> v1Handlers = handlersFor(v1, req -> Response.ok(Map.of("v", 1)));
    Map<String, RequestHandler> v2Handlers = handlersFor(v2, req -> Response.ok(Map.of("v", 2)));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, v1Handlers)
            .addSpec(v2, v2Handlers)
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      assertThat(get("http://localhost:" + port + "/api/v1/ping").body()).contains("\"v\":1");
      assertThat(get("http://localhost:" + port + "/api/v2/ping").body()).contains("\"v\":2");
      assertThat(get("http://localhost:" + port + "/").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  private static Map<String, RequestHandler> handlersFor(Spec spec, RequestHandler shared) {
    Map<String, RequestHandler> out = new LinkedHashMap<>();
    spec.operations().forEach(op -> out.put(op.operationId(), shared));
    return out;
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
```

- [x] **Step 2: Run it**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. The IT runs under Failsafe (matches `*IT.java`).

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/MultiSpecServerIT.java
git commit -m "test: Integration test for multi-spec server under real HttpServer"
```

---

### Task 11: README — document `addSpec()`

**Files:**
- Modify: `README.md`

- [x] **Step 1: Find the existing single-spec example and add a multi-spec section beneath it**

Use Read to locate the current "Quick start" or builder example in `README.md`, then add a "Multiple specs" subsection containing the example from §4 of the design doc (the `v1Spec` / `v2Spec` `addSpec` example). Keep the wording aligned with the design doc — the public contract is that each call is one binding and namespaces are per-spec.

- [x] **Step 2: Run pre-commit**

Run: `pre-commit run --files README.md`
Expected: PASS (editorconfig, trailing whitespace, end-of-file).

- [x] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: Document addSpec() for multi-spec servers"
```

---

### Task 12: Final verification

- [ ] **Step 1: Full unit + integration run**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. All existing 440 tests plus the new tests pass. Coverage report at `target/site/jacoco/`.

- [ ] **Step 2: Sonar pre-push scan** (per project memory `feedback_sonar_pre_push.md`)

Per memory, SonarLint MCP is blind to worktrees (`feedback_sonarlint_blind_to_worktrees.md`); rely on the CI Sonar scan for the branch. Push and let CI report.

- [ ] **Step 3: Push the branch**

Per memory `reference_gh_token.md`, gh CLI can't open PRs in this repo. Push the branch and ask the user to open the PR manually:

```bash
git push -u origin feat/multiple-specs
```

Then post a one-line summary asking the user to open the PR.

---

## Self-review

**Spec coverage**

- §3 constraint 1 (per-spec namespace) — Task 6 (identical operationIds dispatch independently).
- §3 constraint 2 (backward-compatible builder + reject mixing) — Task 9.
- §3 constraint 3 (duplicate basePath rejection only) — Task 7.
- §3 constraint 4 (global hooks) — implicit; no per-spec hook plumbing added. `HandlerConfig` keeps `interceptors`/`decorators`/`afterHooks`/`exceptionHandler` at server scope (Task 2).
- §4 public API (`addSpec` x2 overloads, mix-rejection error) — Task 5.
- §5 `SpecBinding` record and per-binding HttpContext — Tasks 1, 2.
- §6 filters become per-binding instances — Task 2 (instantiates filters inside the binding loop).
- §7 error cases — Tasks 7, 8, 9 cover duplicate basePath, missing validator, mixed-form. Task 5 implements the "extras vs basePath" cross-check. The "no bindings" case is covered by the `requireNonNull(spec, ...)` path in `build()` (legacy synthesis still requires `spec` non-null) plus the empty-bindings guard in the `OpenApiServer` constructor (Task 2).
- §8 testing — Tasks 4, 6, 7, 8, 9, 10. No new fixture files (Task 3 helper).
- §9 migration — Tasks 2 & 5 keep the legacy builder methods source-compatible; the existing test suite acts as regression.

**Placeholder scan** — no TBDs, no "add appropriate error handling", every code step contains the actual code. The Task 8 assumption (`assumeTrue`) is conditional rather than placeholder — it intentionally skips if the fixture has no security; if so, the case is already covered by the existing `OpenApiServerTest` security wiring tests against the same fixture.

**Type consistency** — `SpecBinding.of(spec, handlers, validators)` factory used consistently in Tasks 2 and 5. `HandlerConfig` record fields aligned between Task 2 (record change) and Task 5 (callsite). `bindings` field added in Task 5, never referenced before. `effectiveBindings` local variable name used only within `build()`.
