# After-Response Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `AfterResponseHook` (global, builder-registered) and `Request.afterResponse(Runnable)` (per-request) hooks that run after the HTTP response has been sent — on the request virtual thread, inside the library's `ScopedValue` binding, with all exceptions ignored.

**Architecture:** Fold the existing `ExceptionFilter`'s job into `RequestPreparationFilter` so hooks fire inside the still-bound `ScopedValue.where(DispatchHandler.CURRENT, request)` block — no thread hand-off, no scope rebinding. After-hook queues live as a mutable list shared between the original `Request` and any `withPrincipals(...)` copy. Error-path responses are synthesised from `HttpExchange` (status + headers; body null since bytes are gone).

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver`, JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-05-20-after-response-hook-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/retailsvc/http/AfterResponseHook.java` — public functional interface.
- `src/test/java/com/retailsvc/http/AfterResponseHookIT.java` — end-to-end behavior with a live `OpenApiServer`.

**Modify:**
- `src/main/java/com/retailsvc/http/Request.java` — add internal `List<Runnable>` queue, `afterResponse(Runnable)` method, package-private `afterHooks()` getter; thread the queue through `withPrincipals(...)`.
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — take `ExceptionHandler` and `List<AfterResponseHook>` as constructor args; restructure `doFilter` to do its own exception handling inside the scoped block and fire hooks afterwards.
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — extend `HandlerConfig` with `afterHooks`, drop `ExceptionFilter` from the OpenAPI chain (keep it for extras), pass the new deps into `RequestPreparationFilter`, add `Builder.afterResponseHook(...)`.
- `src/test/java/com/retailsvc/http/RequestTest.java` (if missing, create) — unit tests for the new queue methods.

**Untouched but worth a glance during implementation:**
- `src/main/java/com/retailsvc/http/internal/SecurityFilter.java` — calls `withPrincipals`; verify queue propagation works through it.
- `src/main/java/com/retailsvc/http/internal/DispatchHandler.java` — renders the success-path response; no changes needed.
- `src/main/java/com/retailsvc/http/internal/ExceptionFilter.java` — stays as-is for extras contexts; no longer used on the OpenAPI chain.

---

## Task 1: Add `AfterResponseHook` public interface

**Files:**
- Create: `src/main/java/com/retailsvc/http/AfterResponseHook.java`

- [ ] **Step 1: Create the interface**

```java
package com.retailsvc.http;

/**
 * Callback invoked after an HTTP response has been written to the client. Runs on the same virtual
 * thread that handled the request, inside the library's request {@link ScopedValue} binding.
 *
 * <p>Hooks fire only when a {@link Request} was successfully constructed — i.e., routing and
 * parameter/body validation passed. Pre-request failures (404, 405, 400 validation) do not fire
 * hooks.
 *
 * <p>On the error path, {@link Response#body()} is always {@code null} because the body bytes
 * have already been sent. {@link Response#status()} and {@link Response#headers()} reflect what
 * was written to the wire.
 *
 * <p>Exceptions thrown by a hook are logged at DEBUG and swallowed; subsequent hooks still run.
 * Hooks compose in registration order.
 */
@FunctionalInterface
public interface AfterResponseHook {

  void after(Request request, Response response);
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/retailsvc/http/AfterResponseHook.java
SKIP=commitlint git commit -m "feat: Add AfterResponseHook interface"
```

---

## Task 2: Add the per-request queue to `Request`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/Request.java`
- Test: `src/test/java/com/retailsvc/http/RequestTest.java`

The queue must survive `withPrincipals(...)` — the new instance shares the *same* `List<Runnable>` reference so runnables queued post-security are visible to the runner that holds the original `Request`.

- [ ] **Step 1: Write the failing unit tests**

Check first whether `RequestTest.java` exists:

```bash
ls src/test/java/com/retailsvc/http/RequestTest.java 2>/dev/null && echo EXISTS || echo MISSING
```

If it exists, append the test methods below into the existing class. If it's missing, create the file with this scaffolding plus the methods. Statically import `org.assertj.core.api.Assertions.*` and `org.junit.jupiter.api.Assertions.assertThrows`.

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTest {

  private static Request newRequest() {
    return new Request(
        new byte[0],
        null,
        null,
        "op",
        Map.of(),
        null,
        name -> null);
  }

  @Test
  void afterResponseRejectsNull() {
    Request request = newRequest();
    assertThrows(NullPointerException.class, () -> request.afterResponse(null));
  }

  @Test
  void afterResponseQueuesInOrder() {
    Request request = newRequest();
    List<String> log = new ArrayList<>();
    request.afterResponse(() -> log.add("first"));
    request.afterResponse(() -> log.add("second"));

    for (Runnable r : request.afterHooks()) {
      r.run();
    }

    assertThat(log).containsExactly("first", "second");
  }

  @Test
  void withPrincipalsSharesAfterHookQueue() {
    Request original = newRequest();
    List<String> log = new ArrayList<>();
    original.afterResponse(() -> log.add("from-original"));

    Request enriched = original.withPrincipals(Map.of("scheme", "principal"));
    enriched.afterResponse(() -> log.add("from-enriched"));

    for (Runnable r : original.afterHooks()) {
      r.run();
    }

    assertThat(log).containsExactly("from-original", "from-enriched");
    assertThat(original.afterHooks()).isSameAs(enriched.afterHooks());
  }
}
```

- [ ] **Step 2: Run tests and confirm they fail to compile**

Run: `mvn -q test -Dtest=RequestTest`
Expected: compilation failure (`afterResponse`, `afterHooks` not found).

- [ ] **Step 3: Add the queue and methods to `Request`**

Edit `src/main/java/com/retailsvc/http/Request.java`:

1. Add the field below `private Map<String, String> queryParamCache;`:

```java
  private final java.util.List<Runnable> afterHooks;
```

(Use the existing `java.util.List` import — already imported via `LinkedHashMap` path? Verify by adding `import java.util.List;` and `import java.util.ArrayList;` at the top with the other `java.util` imports.)

2. In **both** public constructors, initialise the field. The 7-arg constructor delegates to the 8-arg one, so only the 8-arg constructor needs the assignment. Add at the end of the 8-arg constructor body:

```java
    this.afterHooks = new ArrayList<>();
```

3. Add a **package-private** constructor used by `withPrincipals` to share the queue. Place it directly below the 8-arg public constructor:

```java
  // Package-private: lets withPrincipals(...) thread the after-hook queue through so that
  // runnables registered on either the original Request or the principals-enriched copy
  // land in the same backing list.
  Request(
      byte[] body,
      Object parsed,
      TypeMapper bodyMapper,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup,
      Map<String, Object> principals,
      List<Runnable> afterHooks) {
    this.body = body;
    this.parsed = parsed;
    this.bodyMapper = bodyMapper;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.rawQuery = rawQuery;
    this.headerLookup = headerLookup;
    this.principals = Map.copyOf(principals);
    this.afterHooks = afterHooks;
  }
```

Annotate it with `@SuppressWarnings("java:S107")` (same rationale as the 8-arg public ctor).

4. Replace `withPrincipals(...)` to use the new 9-arg constructor so the queue is shared:

```java
  public Request withPrincipals(Map<String, Object> principals) {
    return new Request(
        body,
        parsed,
        bodyMapper,
        operationId,
        pathParameters,
        rawQuery,
        headerLookup,
        principals,
        afterHooks);
  }
```

5. Add the public queue API and package-private getter at the bottom of the class (above `parseQuery`):

```java
  /**
   * Queues a {@link Runnable} to execute after the HTTP response has been sent to the client.
   * Runs on the request thread inside the library's request {@link ScopedValue} binding.
   * Multiple calls queue FIFO. Exceptions thrown by the runnable are logged at DEBUG and
   * swallowed.
   *
   * <p>Calls made after the runner has snapshotted the queue (e.g. from inside a running
   * hook, or from a leaked {@code Request} reference held past the response) are silently
   * ignored.
   *
   * @throws NullPointerException if {@code runnable} is null
   */
  public void afterResponse(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    afterHooks.add(runnable);
  }

  /** Package-private accessor for the after-hook queue; used by RequestPreparationFilter. */
  List<Runnable> afterHooks() {
    return afterHooks;
  }
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `mvn -q test -Dtest=RequestTest`
Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Run the full unit suite to confirm no regressions**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass (378+).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/Request.java src/test/java/com/retailsvc/http/RequestTest.java
SKIP=commitlint git commit -m "feat: Add per-request after-hook queue to Request"
```

---

## Task 3: Add `Builder.afterResponseHook(...)` wiring (no execution yet)

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

This task only adds plumbing. Hooks are stored on the builder and threaded into `HandlerConfig`. They aren't fired yet — that comes in Task 5.

- [ ] **Step 1: Extend `HandlerConfig`**

In `OpenApiServer.java`, update the record:

```java
  record HandlerConfig(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators,
      ExceptionHandler exceptionHandler,
      Map<String, HttpHandler> extras,
      Map<String, SchemeValidator> securityValidators,
      boolean externalAuth,
      List<AfterResponseHook> afterHooks) {}
```

- [ ] **Step 2: Add the builder field and method**

In `Builder`, beside the existing `interceptors` field:

```java
    private final List<AfterResponseHook> afterHooks = new ArrayList<>();
```

And a method (place beside `interceptor(...)`):

```java
    /**
     * Registers an {@link AfterResponseHook} invoked after each response is sent. Hooks run on
     * the request thread inside the library's request scope, in registration order, with all
     * exceptions swallowed. Hooks fire only when a {@link Request} was successfully built —
     * pre-request failures (404, 405, 400 validation) do not fire hooks.
     */
    public Builder afterResponseHook(AfterResponseHook hook) {
      afterHooks.add(requireNonNull(hook, "hook must not be null"));
      return this;
    }
```

- [ ] **Step 3: Pass through in `build()`**

Update the `HandlerConfig` construction in `build()` to include the new list:

```java
      HandlerConfig handlerConfig =
          new HandlerConfig(
              handlers,
              interceptors,
              decorators,
              exceptionHandler,
              extras,
              Map.copyOf(securityValidators),
              externalAuth,
              List.copyOf(afterHooks));
```

- [ ] **Step 4: Compile**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run all unit tests as smoke check**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java
SKIP=commitlint git commit -m "feat: Wire AfterResponseHook through HandlerConfig"
```

---

## Task 4: Fold exception handling into `RequestPreparationFilter`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

After this task, the OpenAPI route's exception handling lives in `RequestPreparationFilter`. `ExceptionFilter` remains only on `extraRoute` contexts. Behavior should be byte-for-byte identical to before (no after-hooks yet); existing tests must keep passing.

- [ ] **Step 1: Add `ExceptionHandler` and `List<AfterResponseHook>` to RPF**

Edit `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`. Add imports:

```java
import com.retailsvc.http.AfterResponseHook;
import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Response;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add fields and update the constructor:

```java
  private static final Logger LOG = LoggerFactory.getLogger(RequestPreparationFilter.class);

  private final Spec spec;
  private final Router router;
  private final Validator validator;
  private final Map<String, TypeMapper> bodyMappers;
  private final ExceptionHandler exceptionHandler;
  private final List<AfterResponseHook> afterHooks;

  public RequestPreparationFilter(
      Spec spec,
      Router router,
      Validator validator,
      Map<String, TypeMapper> bodyMappers,
      ExceptionHandler exceptionHandler,
      List<AfterResponseHook> afterHooks) {
    this.spec = spec;
    this.router = router;
    this.validator = validator;
    this.bodyMappers = Map.copyOf(bodyMappers);
    this.exceptionHandler = exceptionHandler;
    this.afterHooks = List.copyOf(afterHooks);
  }
```

- [ ] **Step 2: Restructure `doFilter`**

Replace the existing `doFilter` body. The new control flow:

1. Routing + parameter/body validation happens outside any try/catch (it can throw — those are pre-Request failures).
2. After the `Request` is built, bind the scoped value and run the inner chain inside a `try/catch`. The chain's exceptions are routed to the user's `ExceptionHandler` so the error response is rendered before we leave the bound scope.
3. After the inner chain returns (either normally or post-recovery), fire after-hooks. Still inside the bound scope.
4. Pre-Request throws are caught at the outer level and routed to the `ExceptionHandler`. No hooks fire.

```java
  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    Request request;
    try {
      request = buildRequest(exchange);
    } catch (RuntimeException | IOException t) {
      exceptionHandler.handle(exchange, t);
      return;
    }

    try {
      ScopedValue.where(DispatchHandler.CURRENT, request)
          .call(
              () -> {
                runInnerChain(exchange, chain);
                fireAfterHooks(exchange, request);
                return null;
              });
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private Request buildRequest(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();

    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    String path = stripBasePath(exchange.getRequestURI().getPath());

    var matchOpt = router.match(method, path);
    if (matchOpt.isEmpty()) {
      var allowed = router.allowedMethods(path);
      if (allowed.isEmpty()) {
        throw new NotFoundException(method + " " + path);
      }
      throw new MethodNotAllowedException(allowed);
    }
    Router.Match match = matchOpt.get();

    Operation op = match.operation();
    validateParameters(exchange, op, match.pathParameters());
    ParsedBody parsedBody = validateAndParseBody(exchange, op, body);

    var headers = exchange.getRequestHeaders();
    return new Request(
        body,
        parsedBody.value(),
        parsedBody.mapper(),
        op.operationId(),
        match.pathParameters(),
        exchange.getRequestURI().getRawQuery(),
        headers::getFirst);
  }

  private void runInnerChain(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      exceptionHandler.handle(exchange, t);
    }
  }
```

`fireAfterHooks` is added in Task 5; for now use an empty stub so the file compiles:

```java
  private void fireAfterHooks(HttpExchange exchange, Request request) {
    // implemented in Task 5
  }
```

- [ ] **Step 3: Remove `ExceptionFilter` from the OpenAPI chain in `OpenApiServer`**

Edit `OpenApiServer.java` constructor. Replace the three lines that add filters to the OpenAPI context:

```java
    ctx.getFilters().add(new ExceptionFilter(exceptionHandler));
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, bodyMappers));
    ctx.getFilters()
```

with:

```java
    ctx.getFilters()
        .add(
            new RequestPreparationFilter(
                spec,
                router,
                validator,
                bodyMappers,
                exceptionHandler,
                handlerConfig.afterHooks()));
    ctx.getFilters()
```

(The `SecurityFilter` line that follows stays unchanged.)

Leave the `ExceptionFilter` install for extras routes intact (further down in the constructor):

```java
    for (Map.Entry<String, HttpHandler> e : handlerConfig.extras().entrySet()) {
      HttpContext extraCtx = httpServer.createContext(e.getKey());
      extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
      extraCtx.setHandler(e.getValue());
    }
```

- [ ] **Step 4: Compile**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run the full unit + integration test suite**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. All existing tests pass — moving exception handling into RPF should be observationally identical.

If anything fails, the most likely culprits are:
- A test that introspects the filter chain by class (search `instanceof ExceptionFilter` or `ExceptionFilter.class` in test sources).
- A test relying on ExceptionFilter running on extras routes — should still work, behavior preserved.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java src/main/java/com/retailsvc/http/OpenApiServer.java
SKIP=commitlint git commit -m "refactor: Fold exception handling into RequestPreparationFilter"
```

---

## Task 5: Implement after-hook execution

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`
- Modify: `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`

DispatchHandler stashes the rendered `Response` as an exchange attribute so the runner can pass it to global hooks. On the error path, the runner builds a synthetic `Response` from the exchange.

- [ ] **Step 1: Have DispatchHandler stash the rendered Response**

Edit `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`. Replace the `handle` method:

```java
  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();
  public static final String RESPONSE_ATTR = "com.retailsvc.http.response";

  ...

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler handler = handlers.get(request.operationId());
    if (handler == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    Response response = invoke(0, request, handler);
    for (ResponseDecorator decorator : decorators) {
      response = decorator.decorate(request, response);
    }
    exchange.setAttribute(RESPONSE_ATTR, response);
    renderer.render(exchange, response);
  }
```

- [ ] **Step 2: Implement `fireAfterHooks` in RequestPreparationFilter**

Replace the stub `fireAfterHooks` introduced in Task 4. Imports already needed should be in place; add `com.sun.net.httpserver.Headers` and `java.util.LinkedHashMap` if not already imported.

```java
  private void fireAfterHooks(HttpExchange exchange, Request request) {
    Response response = resolveResponse(exchange);
    List<Runnable> snapshot = List.copyOf(request.afterHooks());

    for (AfterResponseHook hook : afterHooks) {
      try {
        hook.after(request, response);
      } catch (Throwable t) {
        LOG.debug("after-response hook threw", t);
      }
    }
    for (Runnable runnable : snapshot) {
      try {
        runnable.run();
      } catch (Throwable t) {
        LOG.debug("after-response runnable threw", t);
      }
    }
  }

  private static Response resolveResponse(HttpExchange exchange) {
    Object stashed = exchange.getAttribute(DispatchHandler.RESPONSE_ATTR);
    if (stashed instanceof Response r) {
      return r;
    }
    Headers headers = exchange.getResponseHeaders();
    String contentType = headers.getFirst("Content-Type");
    Map<String, String> flat = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      List<String> values = e.getValue();
      if (values != null && !values.isEmpty()) {
        flat.put(e.getKey(), values.get(0));
      }
    }
    return new Response(exchange.getResponseCode(), null, contentType, flat);
  }
```

Note: `List.copyOf(request.afterHooks())` snapshots the queue so runnables added during hook execution don't recurse.

- [ ] **Step 3: Compile**

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the full suite — still no new tests yet**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. All existing tests still pass; the new code is just dormant code paths for tests that don't register any hooks.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java src/main/java/com/retailsvc/http/internal/DispatchHandler.java
SKIP=commitlint git commit -m "feat: Fire after-response hooks inside the request scope"
```

---

## Task 6: Integration tests for end-to-end behavior

**Files:**
- Create: `src/test/java/com/retailsvc/http/AfterResponseHookIT.java`

This is the meat of the verification. Each test builds a fresh `OpenApiServer` against the existing `src/test/resources/openapi.json` (or whichever fixture the rest of the IT suite uses — verify before writing), registers handlers and hooks, makes a real HTTP request, and asserts hook side effects.

- [ ] **Step 1: Find the canonical IT harness**

Run: `ls src/test/java/com/retailsvc/http/*IT.java` and pick one (e.g. `OpenApiServerIT.java`) to mirror the boot pattern.

Run: `mvn -q test-compile 2>&1 | tail -5` to make sure compile is clean before writing tests.

- [ ] **Step 2: Write the integration test class**

Copy the boot scaffolding from the chosen existing IT. The skeleton (adapt the spec-load mechanic to whatever the existing IT does — likely a JSON load via Gson or Jackson):

```java
package com.retailsvc.http;

import static java.net.http.HttpClient.newHttpClient;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.spec.Spec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AfterResponseHookIT {

  // Reuse whatever helper the other ITs use to load the spec and pick a handler operation.
  // Replace these constants with the operationId / path / method actually present in the fixture.
  private static final String OK_OPERATION_ID = "getThings"; // <-- adapt
  private static final String OK_PATH = "/things";           // <-- adapt
  private static final String NOT_FOUND_PATH = "/does-not-exist";

  private static Spec loadSpec() {
    // Mirror the existing IT's loader.
    return TestSpecs.openapi(); // <-- replace with the actual call
  }

  private static URI uri(OpenApiServer server, String path) {
    return URI.create("http://localhost:" + server.listenPort() + path);
  }

  @Test
  void globalHookFiresAfterSuccessfulResponse() throws Exception {
    AtomicReference<Request> capturedRequest = new AtomicReference<>();
    AtomicReference<Response> capturedResponse = new AtomicReference<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(Map.of(OK_OPERATION_ID, req -> Response.status(204)))
            .afterResponseHook(
                (req, resp) -> {
                  capturedRequest.set(req);
                  capturedResponse.set(resp);
                })
            .build()) {

      HttpResponse<Void> resp =
          newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(204);
      assertThat(capturedRequest.get()).isNotNull();
      assertThat(capturedRequest.get().operationId()).isEqualTo(OK_OPERATION_ID);
      assertThat(capturedResponse.get()).isNotNull();
      assertThat(capturedResponse.get().status()).isEqualTo(204);
    }
  }

  @Test
  void perRequestRunnablesFireInOrder() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(
                Map.of(
                    OK_OPERATION_ID,
                    req -> {
                      req.afterResponse(() -> log.add("first"));
                      req.afterResponse(() -> log.add("second"));
                      return Response.status(204);
                    }))
            .build()) {

      newHttpClient()
          .send(
              HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
              BodyHandlers.discarding());

      // Hooks run on the request thread before the JDK server moves on. The HTTP client
      // already saw the response bytes; the hook ran synchronously after the bytes were
      // flushed.
      assertThat(log).containsExactly("first", "second");
    }
  }

  @Test
  void hookExceptionDoesNotAffectClientOrOtherHooks() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(Map.of(OK_OPERATION_ID, req -> Response.status(204)))
            .afterResponseHook((req, resp) -> { throw new RuntimeException("boom"); })
            .afterResponseHook((req, resp) -> log.add("second-ran"))
            .build()) {

      HttpResponse<Void> resp =
          newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(204);
      assertThat(log).containsExactly("second-ran");
    }
  }

  @Test
  void hookFiresOnHandlerException() throws Exception {
    AtomicReference<Response> capturedResponse = new AtomicReference<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(
                Map.of(
                    OK_OPERATION_ID,
                    req -> { throw new RuntimeException("kapow"); }))
            .afterResponseHook((req, resp) -> capturedResponse.set(resp))
            .build()) {

      HttpResponse<Void> resp =
          newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(500);
      assertThat(capturedResponse.get()).isNotNull();
      assertThat(capturedResponse.get().status()).isEqualTo(500);
      assertThat(capturedResponse.get().body()).isNull();
    }
  }

  @Test
  void preRequestFailureSkipsHooks() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(Map.of(OK_OPERATION_ID, req -> Response.status(204)))
            .afterResponseHook((req, resp) -> log.add("fired"))
            .build()) {

      HttpResponse<Void> resp =
          newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, NOT_FOUND_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(404);
      assertThat(log).isEmpty();
    }
  }

  @Test
  void hookSeesScopedRequestAndSameThreadAsHandler() throws Exception {
    AtomicReference<Thread> handlerThread = new AtomicReference<>();
    AtomicReference<Thread> hookThread = new AtomicReference<>();
    AtomicReference<Request> hookScopedRequest = new AtomicReference<>();

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(loadSpec())
            .port(0)
            .handlers(
                Map.of(
                    OK_OPERATION_ID,
                    req -> {
                      handlerThread.set(Thread.currentThread());
                      return Response.status(204);
                    }))
            .afterResponseHook(
                (req, resp) -> {
                  hookThread.set(Thread.currentThread());
                  hookScopedRequest.set(DispatchHandler.CURRENT.get());
                })
            .build()) {

      newHttpClient()
          .send(
              HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
              BodyHandlers.discarding());

      assertThat(hookThread.get()).isSameAs(handlerThread.get());
      assertThat(hookScopedRequest.get()).isNotNull();
      assertThat(hookScopedRequest.get().operationId()).isEqualTo(OK_OPERATION_ID);
    }
  }
}
```

**Adapter notes the implementer must resolve before running:**
- The spec-loading helper (`TestSpecs.openapi()` placeholder) needs to be replaced with the loader the other ITs use. Open one IT and copy the call.
- `OK_OPERATION_ID` / `OK_PATH` must match an operation in `src/test/resources/openapi.json` that accepts `GET` and has no required body. Pick one or add a no-op operation if none exists. `NOT_FOUND_PATH` must NOT be served by the spec.

- [ ] **Step 3: Run the integration tests and verify they fail to compile (placeholders)**

Run: `mvn -q test-compile -DskipTests=false 2>&1 | tail -20`
Expected: compile errors point at the placeholder constants/helpers. Resolve them.

- [ ] **Step 4: Fix the placeholders, then run the integration tests**

Run: `mvn -q verify -Dit.test=AfterResponseHookIT`
Expected: BUILD SUCCESS, all 6 tests pass.

If any test fails, prioritise fixing rather than retreating to mocks. Likely culprits:
- `perRequestRunnablesFireInOrder` race: the runnable runs synchronously on the request thread, but the test client returned as soon as bytes were flushed. The JDK `HttpServer` writes via `OutputStream.close()` inside `ResponseRenderer.render`, so by the time `chain.doFilter` returns we are guaranteed past send. The hook runs before `runInnerChain` returns. Still synchronous on the request thread, so the asserted list is populated by the time the client read returns. If you see flakiness, add a short busy-wait — but it should not be necessary.

- [ ] **Step 5: Run the entire suite to make sure nothing else regressed**

Run: `mvn -q verify`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/retailsvc/http/AfterResponseHookIT.java
SKIP=commitlint git commit -m "test: Add integration tests for after-response hooks"
```

---

## Task 7: Builder NPE test

**Files:**
- Modify or create: a small unit test next to existing builder-validation tests.

- [ ] **Step 1: Locate the builder test class**

Run: `ls src/test/java/com/retailsvc/http/ | grep -i builder` and pick the file (likely `OpenApiServerBuilderTest.java` or similar). If none exists, add the test method into a class named consistently with siblings.

- [ ] **Step 2: Add the NPE test**

```java
  @Test
  void afterResponseHookRejectsNull() {
    assertThrows(
        NullPointerException.class,
        () -> OpenApiServer.builder().afterResponseHook(null));
  }
```

- [ ] **Step 3: Run the test**

Run: `mvn -q test -Dtest=<TestClassName>#afterResponseHookRejectsNull`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add <test file path>
SKIP=commitlint git commit -m "test: Builder.afterResponseHook null-guard"
```

---

## Task 8: README + javadoc polish

**Files:**
- Modify: `README.md`
- Touch as needed: javadoc on `OpenApiServer.Builder.afterResponseHook`, `Request.afterResponse`, and `AfterResponseHook` — already drafted in earlier tasks; just verify consistency.

- [ ] **Step 1: Find the section to extend**

Run: `grep -n "interceptor\|ResponseDecorator\|exceptionHandler" README.md | head -20` — pick the most natural spot (typically the "Hooks" / "Filters" / "Extensibility" section).

- [ ] **Step 2: Add a short subsection**

Example wording (adapt to the README's existing voice):

````markdown
### After-response hooks

Register code to run after the response has been sent. Hooks run on the request virtual thread,
inside the library's request scope, with exceptions swallowed.

```java
OpenApiServer.builder()
    .afterResponseHook((req, resp) ->
        metrics.timer("http.request").record(req.operationId(), resp.status()))
    .handlers(...)
    .build();
```

Handlers can also queue per-request runnables:

```java
Map<String, RequestHandler> handlers = Map.of(
    "getThings", req -> {
      req.afterResponse(() -> auditLog.flush());
      return Response.ok(things);
    });
```

Global hooks run first (registration order), then per-request runnables (FIFO). Pre-request
failures (404, 405, validation) do not fire hooks.
````

- [ ] **Step 3: Commit**

```bash
git add README.md
SKIP=commitlint git commit -m "docs: Document after-response hooks"
```

---

## Task 9: SonarLint sweep + final verify

Per project memory, run SonarLint MCP on every file touched in this branch before pushing, and fix any new findings.

- [ ] **Step 1: Touched files inventory**

```
src/main/java/com/retailsvc/http/AfterResponseHook.java
src/main/java/com/retailsvc/http/Request.java
src/main/java/com/retailsvc/http/OpenApiServer.java
src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java
src/main/java/com/retailsvc/http/internal/DispatchHandler.java
src/test/java/com/retailsvc/http/RequestTest.java
src/test/java/com/retailsvc/http/AfterResponseHookIT.java
src/test/java/com/retailsvc/http/<BuilderTest>.java
README.md
```

Run SonarLint MCP `analyzeFile` for each `.java` file in the list. Fix every NEW issue in the same branch. (Note: per project memory, SonarLint MCP cannot read files only present in the worktree — if it returns `not_found`, rely on CI scan for that file.)

- [ ] **Step 2: Final verify**

Run: `mvn -q verify`
Expected: BUILD SUCCESS, all tests pass, JaCoCo report generated.

- [ ] **Step 3: Confirm POM is sorted**

Run: `mvn -q sortpom:verify`
Expected: BUILD SUCCESS (no diff).

- [ ] **Step 4: Push the branch**

```bash
git push -u origin feat/after-hook
```

(Per project memory, do NOT attempt to open a PR with `gh` — let the user do it manually.)

- [ ] **Step 5: Report**

Summarise: tasks complete, test counts, branch pushed. Hand off to the user to open the PR.
