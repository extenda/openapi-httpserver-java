# Extra (non-OpenAPI) Handlers + Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow callers to register `HttpHandler` instances at arbitrary URL paths outside the OpenAPI spec, via a new `OpenApiServer.Builder`. Ship two built-in helpers (`Handlers.aliveHandler`, `Handlers.specHandler`).

**Architecture:** Add a nested `Builder` to `OpenApiServer` that collects required fields and a `LinkedHashMap<String, HttpHandler>` of "extras". `build()` instantiates the server via a new package-private constructor that mounts each extra as its own `HttpContext` wrapped in `ExceptionFilter` only — no validation, no dispatch. Existing public constructors stay as thin delegators for back-compat. Two helpers in `Handlers`: `aliveHandler` (204 No Content on GET/HEAD, 405 with `Allow: GET, HEAD` otherwise) and `specHandler(String classpathResource)` (eager-load bytes, content-type by extension).

**Tech Stack:** Java 25, `com.sun.net.httpserver`, JUnit 5, AssertJ, java.net.http HttpClient. Build: Maven.

**Spec:** `docs/superpowers/specs/2026-05-12-extra-handlers-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java` — package-private `HttpHandler` backing `Handlers.specHandler`, holds cached bytes + content type.
- `src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java` — package-private `HttpHandler` wrapper that allows only GET/HEAD and returns 405 with `Allow: GET, HEAD` otherwise. Shared by `aliveHandler` and `specHandler`.
- `src/test/java/com/retailsvc/http/HandlersTest.java` — unit tests for `aliveHandler` and `specHandler`.
- `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java` — unit tests for the builder validation rules.
- `src/test/java/com/retailsvc/http/ExtraHandlersIT.java` — integration tests for extras mounted on a running server.

**Modify:**
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — add nested `Builder`, add package-private constructor accepting extras, delegate existing public constructors to the builder.
- `src/main/java/com/retailsvc/http/Handlers.java` — add `aliveHandler()` and `specHandler(String)` public statics.
- `README.md` — replace constructor example with builder example, add subsection on extras.

---

## Task 1: `MethodLimitedHandler` (shared GET/HEAD-only wrapper)

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java`
- Test: `src/test/java/com/retailsvc/http/HandlersTest.java` (we exercise this indirectly via `aliveHandler`/`specHandler`, but cover its behavior here through the public helpers in later tasks)

- [ ] **Step 1: Create the wrapper class**

```java
package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Wraps a delegate handler so it answers only GET and HEAD. Other methods produce 405 with
 * {@code Allow: GET, HEAD}.
 */
public final class MethodLimitedHandler implements HttpHandler {

  private static final String ALLOW = "GET, HEAD";

  private final HttpHandler delegate;

  public MethodLimitedHandler(HttpHandler delegate) {
    this.delegate = delegate;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method) || "HEAD".equals(method)) {
      delegate.handle(exchange);
      return;
    }
    try (exchange) {
      exchange.getResponseHeaders().add("Allow", ALLOW);
      exchange.sendResponseHeaders(HTTP_BAD_METHOD, -1);
    }
  }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java
git commit -m "feat: add MethodLimitedHandler wrapper for GET/HEAD-only routes"
```

---

## Task 2: `ClasspathResourceHandler` (bytes cached, content-type inferred)

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java`

- [ ] **Step 1: Create the handler**

```java
package com.retailsvc.http.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Serves bytes loaded eagerly from a classpath resource. Content-Type is inferred from the file
 * extension. Throws {@link IllegalArgumentException} if the resource is missing.
 */
public final class ClasspathResourceHandler implements HttpHandler {

  private final byte[] bytes;
  private final String contentType;

  public ClasspathResourceHandler(String classpathResource) {
    try (InputStream in = ClasspathResourceHandler.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
      }
      this.bytes = in.readAllBytes();
    } catch (IOException io) {
      throw new IllegalArgumentException("failed reading classpath resource: " + classpathResource, io);
    }
    this.contentType = contentTypeFor(classpathResource);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
      if ("HEAD".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(200, -1);
        return;
      }
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static String contentTypeFor(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return "application/json";
    }
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
      return "application/yaml";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    return "application/octet-stream";
  }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java
git commit -m "feat: add ClasspathResourceHandler for static resource serving"
```

---

## Task 3: `Handlers.aliveHandler()` and `Handlers.specHandler(...)` — TDD

**Files:**
- Create: `src/test/java/com/retailsvc/http/HandlersTest.java`
- Modify: `src/main/java/com/retailsvc/http/Handlers.java`

- [ ] **Step 1: Write failing tests for `aliveHandler`**

Create `src/test/java/com/retailsvc/http/HandlersTest.java`:

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandlersTest {

  @Test
  void aliveHandlerReturns204OnGet() throws IOException {
    HttpExchange ex = newExchange("GET");
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(204, -1);
  }

  @Test
  void aliveHandlerReturns204OnHead() throws IOException {
    HttpExchange ex = newExchange("HEAD");
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(204, -1);
  }

  @Test
  void aliveHandlerReturns405OnPost() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(405, -1);
    assertThat(headers.getFirst("Allow")).isEqualTo("GET, HEAD");
  }

  private static HttpExchange newExchange(String method) {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn(method);
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    return ex;
  }
}
```

- [ ] **Step 2: Run tests, expect failure (method not defined)**

Run: `mvn -q test -Dtest=HandlersTest`
Expected: COMPILE FAILURE: `cannot find symbol: method aliveHandler()`

- [ ] **Step 3: Add `aliveHandler` to `Handlers`**

In `src/main/java/com/retailsvc/http/Handlers.java`, add the import and method:

```java
import com.retailsvc.http.internal.MethodLimitedHandler;
```

Append before the closing brace of the class:

```java
  /** Returns 204 No Content on GET/HEAD; 405 with {@code Allow: GET, HEAD} otherwise. */
  public static HttpHandler aliveHandler() {
    return new MethodLimitedHandler(
        exchange -> {
          try (exchange) {
            exchange.sendResponseHeaders(204, -1);
          }
        });
  }
```

- [ ] **Step 4: Run tests, expect pass**

Run: `mvn -q test -Dtest=HandlersTest`
Expected: 3 tests pass.

- [ ] **Step 5: Write failing tests for `specHandler`**

Append to `HandlersTest`:

```java
  @Test
  void specHandlerServesYamlWithInferredContentType() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.specHandler("/openapi.yaml").handle(ex);

    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/yaml");
    verify(ex).sendResponseHeaders(org.mockito.ArgumentMatchers.eq(200), org.mockito.ArgumentMatchers.longThat(n -> n > 0));
    assertThat(body.toByteArray()).isNotEmpty();
  }

  @Test
  void specHandlerInfersJsonContentType() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    Handlers.specHandler("/openapi.json").handle(ex);

    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void specHandlerThrowsAtConstructionForMissingResource() {
    assertThatThrownBy(() -> Handlers.specHandler("/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.yaml");
  }

  @Test
  void specHandlerReturns405OnPost() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);

    Handlers.specHandler("/openapi.yaml").handle(ex);

    verify(ex).sendResponseHeaders(405, -1);
    assertThat(headers.getFirst("Allow")).isEqualTo("GET, HEAD");
  }
```

- [ ] **Step 6: Run tests, expect compile failure**

Run: `mvn -q test -Dtest=HandlersTest`
Expected: COMPILE FAILURE: `cannot find symbol: method specHandler(String)`

- [ ] **Step 7: Add `specHandler` to `Handlers`**

Add import:

```java
import com.retailsvc.http.internal.ClasspathResourceHandler;
```

Append before the closing brace of the class:

```java
  /**
   * Serves a classpath resource. Content-Type is inferred from the file extension. The resource is
   * loaded eagerly; a missing resource fails immediately with {@link IllegalArgumentException}.
   *
   * @param classpathResource absolute classpath path, e.g. {@code /schemas/v1/openapi.yaml}
   */
  public static HttpHandler specHandler(String classpathResource) {
    return new MethodLimitedHandler(new ClasspathResourceHandler(classpathResource));
  }
```

- [ ] **Step 8: Run tests, expect pass**

Run: `mvn -q test -Dtest=HandlersTest`
Expected: 7 tests pass (3 alive + 4 spec).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/retailsvc/http/Handlers.java src/test/java/com/retailsvc/http/HandlersTest.java
git commit -m "feat: add Handlers.aliveHandler and Handlers.specHandler"
```

---

## Task 4: `OpenApiServer.Builder` — validation rules (unit tests)

**Files:**
- Create: `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

- [ ] **Step 1: Write failing builder unit tests**

Create `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`:

```java
package com.retailsvc.http;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static java.util.Collections.emptyMap;

import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiServerBuilderTest {

  private final Spec spec = testSpec();
  private final JsonMapper jsonMapper = body -> new java.util.HashMap<String, Object>();

  @Test
  void buildsWithRequiredFieldsOnly() {
    assertDoesNotThrow(
        () -> {
          try (var _ =
              OpenApiServer.builder()
                  .spec(spec)
                  .jsonMapper(jsonMapper)
                  .handlers(emptyMap())
                  .port(0)
                  .build()) {
            // close on exit
          }
        });
  }

  @Test
  void rejectsDuplicateExtraPathOnSecondAddHandler() {
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .jsonMapper(jsonMapper)
            .handlers(emptyMap())
            .addHandler("/alive", Handlers.aliveHandler());

    assertThatThrownBy(() -> b.addHandler("/alive", Handlers.aliveHandler()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/alive");
  }

  @Test
  void rejectsExtraPathEqualToSpecBasePathAtBuildTime() {
    // testSpec() uses "/api" as the basePath (from servers[0].url = http://localhost:8080/api).
    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .jsonMapper(jsonMapper)
                    .handlers(emptyMap())
                    .addHandler("/api", Handlers.aliveHandler())
                    .port(0)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/api");
  }

  @Test
  void rejectsNullSpec() {
    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .jsonMapper(jsonMapper)
                    .handlers(emptyMap())
                    .port(0)
                    .build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec");
  }

  private static Spec testSpec() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "Test API", "version", "1.0"),
            "servers", List.of(Map.of("url", "http://localhost:8080/api")),
            "paths", emptyMap());
    return Spec.from(raw);
  }
}
```

- [ ] **Step 2: Run tests, expect compile failure**

Run: `mvn -q test -Dtest=OpenApiServerBuilderTest`
Expected: COMPILE FAILURE: `cannot find symbol: method builder()`.

- [ ] **Step 3: Add Builder to `OpenApiServer`**

In `src/main/java/com/retailsvc/http/OpenApiServer.java`, add to the imports:

```java
import java.util.LinkedHashMap;
```

Inside the `OpenApiServer` class (e.g. just above the closing brace), add:

```java
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Spec spec;
    private JsonMapper jsonMapper;
    private Map<String, HttpHandler> handlers;
    private ExceptionHandler exceptionHandler;
    private int port = DEFAULT_PORT;
    private final LinkedHashMap<String, HttpHandler> extras = new LinkedHashMap<>();

    private Builder() {}

    public Builder spec(Spec spec) {
      this.spec = spec;
      return this;
    }

    public Builder jsonMapper(JsonMapper jsonMapper) {
      this.jsonMapper = jsonMapper;
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
      requireNonNull(jsonMapper, "JsonMapper must not be null");
      requireNonNull(handlers, "handlers must not be null");
      String basePath = Optional.ofNullable(spec.basePath()).orElse("/");
      for (String extraPath : extras.keySet()) {
        if (extraPath.equals(basePath)) {
          throw new IllegalStateException(
              "extra handler path " + extraPath + " collides with OpenAPI base path " + basePath);
        }
      }
      return new OpenApiServer(spec, jsonMapper, handlers, exceptionHandler, port, extras);
    }
  }
```

- [ ] **Step 4: Add the package-private constructor with extras**

Inside `OpenApiServer`, add a new constructor accepting the extras map. Refactor the existing public constructor to delegate, and add filter wiring for extras:

```java
  OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler,
      int port,
      Map<String, HttpHandler> extras)
      throws IOException {

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

    HttpContext ctx = httpServer.createContext(Optional.ofNullable(spec.basePath()).orElse("/"));
    ctx.getFilters().add(new ExceptionFilter(exceptionHandler));
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, jsonMapper));
    ctx.setHandler(new DispatchHandler(handlers));

    for (Map.Entry<String, HttpHandler> e : extras.entrySet()) {
      HttpContext extraCtx = httpServer.createContext(e.getKey());
      extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
      extraCtx.setHandler(e.getValue());
    }

    httpServer.createContext("/", Handlers.notFoundHandler());
    httpServer.start();

    LOG.info("Server started (port {}) in {}ms", port, System.currentTimeMillis() - t0);
  }
```

Update the two existing public constructors to delegate:

```java
  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler)
      throws IOException {
    this(spec, jsonMapper, handlers, exceptionHandler, DEFAULT_PORT, Map.of());
  }

  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler,
      int port)
      throws IOException {
    this(spec, jsonMapper, handlers, exceptionHandler, port, Map.of());
  }
```

- [ ] **Step 5: Run builder unit tests**

Run: `mvn -q test -Dtest=OpenApiServerBuilderTest`
Expected: 4 tests pass.

- [ ] **Step 6: Run full unit test suite (back-compat check)**

Run: `mvn -q test`
Expected: All existing tests still pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java
git commit -m "feat: add OpenApiServer.Builder with extra-handler support"
```

---

## Task 5: Integration test — extras mounted on a running server

**Files:**
- Create: `src/test/java/com/retailsvc/http/ExtraHandlersIT.java`

- [ ] **Step 1: Write failing IT**

```java
package com.retailsvc.http;

import static com.retailsvc.http.Handlers.defaultExceptionHandler;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtraHandlersIT extends ServerBaseTest {

  @Test
  void aliveExtraReturns204AndBypassesValidation() throws Exception {
    try (var s =
            OpenApiServer.builder()
                .spec(spec)
                .jsonMapper(jsonMapper())
                .handlers(Map.of())
                .exceptionHandler(defaultExceptionHandler())
                .port(0)
                .addHandler("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/alive"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(204);
      assertThat(resp.body()).isEmpty();
    }
  }

  @Test
  void specHandlerServesClasspathResource() throws Exception {
    try (var s =
            OpenApiServer.builder()
                .spec(spec)
                .jsonMapper(jsonMapper())
                .handlers(Map.of())
                .exceptionHandler(defaultExceptionHandler())
                .port(0)
                .addHandler("/openapi.yaml", Handlers.specHandler("/openapi.yaml"))
                .build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/openapi.yaml"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type")).contains("application/yaml");
      assertThat(resp.body()).isNotEmpty();
    }
  }

  @Test
  void extraHandlerExceptionFlowsThroughExceptionHandler() throws Exception {
    HttpHandler boom =
        ex -> {
          throw new RuntimeException("boom");
        };

    try (var s =
            OpenApiServer.builder()
                .spec(spec)
                .jsonMapper(jsonMapper())
                .handlers(Map.of())
                .exceptionHandler(defaultExceptionHandler())
                .port(0)
                .addHandler("/boom", boom)
                .build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/boom"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      // Default exception handler maps unknown throwables to 500 with no body.
      assertThat(resp.statusCode()).isEqualTo(500);
    }
  }

  @Test
  void existingPublicConstructorStillWorks() {
    try {
      try (var s =
          new OpenApiServer(
              spec, jsonMapper(), Map.of(), defaultExceptionHandler(), 0)) {
        assertThat(s.listenPort()).isGreaterThan(0);
      }
    } catch (IOException io) {
      fail(io);
    }
  }
}
```

- [ ] **Step 2: Run integration tests**

Run: `mvn -q verify -Dit.test=ExtraHandlersIT -DfailIfNoTests=false`
Expected: 4 tests pass.

- [ ] **Step 3: Run full verify (catch regressions)**

Run: `mvn -q verify`
Expected: All unit + integration tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/retailsvc/http/ExtraHandlersIT.java
git commit -m "test: integration coverage for extra handlers and builder"
```

---

## Task 6: README updates

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Read the current README example region**

Run: `sed -n '70,110p' README.md`
Note the existing constructor invocation around line 86 — that block is the one to replace.

- [ ] **Step 2: Replace the constructor example with builder example**

In `README.md`, change the example using `new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());` to:

```java
var server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(mapper)
    .handlers(handlers)
    .exceptionHandler(Handlers.defaultExceptionHandler())
    .build();
```

- [ ] **Step 3: Add an "Extra (non-OpenAPI) handlers" subsection**

Append a new subsection after the builder example (or in the most natural location near other usage docs):

````markdown
### Extra (non-OpenAPI) handlers

Mount handlers at arbitrary paths outside the OpenAPI spec — useful for liveness probes,
serving the spec document itself, or any other operational endpoint that should not be subject
to OpenAPI parameter / body validation.

```java
var server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(mapper)
    .handlers(handlers)
    .addHandler("/alive", Handlers.aliveHandler())
    .addHandler("/schemas/v1/openapi.yaml",
                Handlers.specHandler("/schemas/v1/openapi.yaml"))
    .build();
```

Extra handlers bypass OpenAPI validation but are still wrapped in the configured
`ExceptionHandler`, so any uncaught exception is rendered using the same error envelope as
API routes.

Built-in helpers:
- `Handlers.aliveHandler()` — 204 No Content on `GET`/`HEAD`, 405 otherwise.
- `Handlers.specHandler(classpathResource)` — serves a classpath resource (content-type
  inferred from extension). Throws `IllegalArgumentException` at construction if the
  resource is missing.

The original public constructors remain available for back-compat.
````

- [ ] **Step 4: Confirm pre-commit hooks pass**

Run: `pre-commit run --files README.md`
Expected: all hooks pass (whitespace, editorconfig, etc.).

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: builder + extra-handlers usage in README"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full clean build**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS. All unit and integration tests pass.

- [ ] **Step 2: Pre-commit on the whole tree**

Run: `pre-commit run --all-files`
Expected: all hooks pass.

- [ ] **Step 3: Confirm git tree is clean**

Run: `git status`
Expected: nothing to commit, working tree clean.
