# Decorator ScopedValue Scope Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ResponseDecorator` instances run inside the `ScopedValue` bindings established by `RequestInterceptor`s, matching the contract documented in `README.md` ("Combining the two").

**Architecture:** Move the decorator loop from `DispatchHandler.handle` into the base case of the recursive `invoke(...)` helper. Decorators then execute after `handler.handle(request)` but *before* control unwinds through the interceptor stack, so every interceptor's `ScopedValue.where(...).call(...)` frame is still live when a decorator runs. A side effect is that interceptors observe the *decorated* Response on the way back up — explicitly endorsed by the README.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver.HttpServer`, JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-05-23-decorator-scoped-value-scope-design.md`

---

## File Structure

- Modify: `src/main/java/com/retailsvc/http/internal/DispatchHandler.java` — relocate decorator loop.
- Modify: `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java` — three new unit tests pinning the new contract.

No README change. No public API change. No new files.

---

## Task 1: Failing test — decorator sees interceptor-bound ScopedValue

**Files:**
- Modify: `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`

- [ ] **Step 1: Add imports and a class-level test ScopedValue**

Open `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`. Add the following imports near the existing ones (keep alphabetical order with the rest of the file):

```java
import com.retailsvc.http.RequestInterceptor;
import com.retailsvc.http.ResponseDecorator;
import java.util.concurrent.atomic.AtomicReference;
```

Add a static field inside the class body, immediately after the class declaration line:

```java
  private static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
```

- [ ] **Step 2: Add a helper that builds a DispatchHandler with interceptors and decorators**

Add this overload of `dispatcher` to the class (place it directly under the existing `dispatcher` method):

```java
  private static DispatchHandler dispatcher(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators) {
    return new DispatchHandler(handlers, interceptors, decorators, new ResponseRenderer(Map.of()));
  }
```

- [ ] **Step 3: Write the failing test**

Append this test method to the class:

```java
  @Test
  void decoratorSeesInterceptorBoundScopedValue() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    RequestInterceptor bindCid =
        (request, next) -> ScopedValue.where(CORRELATION_ID, "cid-123").call(next::proceed);
    ResponseDecorator stampCid =
        (req, resp) -> resp.withHeader("X-Correlation-Id", CORRELATION_ID.get());

    HttpExchange ex = stubExchange();
    AtomicReference<Response> rendered = new AtomicReference<>();

    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(bindCid), List.of(stampCid)).handle(ex);
          rendered.set((Response) ex.getAttribute(DispatchHandler.RESPONSE_ATTR));
          return null;
        });

    assertThat(rendered.get().headers()).containsEntry("X-Correlation-Id", "cid-123");
  }
```

- [ ] **Step 4: Wire up `exchange.getAttribute` on the stub**

The stub returned by `stubExchange()` is a Mockito mock and `getAttribute(...)` returns `null` by default. Switch the stub to capture the `setAttribute(...)` call. Replace the existing `stubExchange()` body with:

```java
  private static HttpExchange stubExchange() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    Map<String, Object> attrs = new HashMap<>();
    doAnswer(
            inv -> {
              attrs.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(exchange)
        .setAttribute(anyString(), any());
    when(exchange.getAttribute(anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
    return exchange;
  }
```

Add the imports the new stub needs (keep alphabetical):

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import java.util.HashMap;
```

- [ ] **Step 5: Run the new test and confirm it FAILS for the right reason**

Run:

```bash
mvn test -Dtest=DispatchHandlerTest#decoratorSeesInterceptorBoundScopedValue
```

Expected: test fails with `java.util.NoSuchElementException: ScopedValue not bound` thrown from `stampCid` — proves the bug.

If it fails for any other reason (compile error, NPE in the stub, etc.), stop and fix that before continuing — the test must fail *because the bug exists*, not because the test is broken.

- [ ] **Step 6: Commit the failing test**

```bash
git add src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java
git commit -m "test: Decorator should see interceptor-bound ScopedValue"
```

(Pre-commit will run Google Java Format; let it.)

---

## Task 2: Fix — move decorator loop inside the interceptor scope

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`

- [ ] **Step 1: Move the decorator loop into `invoke`'s base case**

Open `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`. Replace the current `handle` and `invoke` methods with:

```java
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler handler = handlers.get(request.operationId());
    Response response = invoke(0, request, handler);
    exchange.setAttribute(RESPONSE_ATTR, response);
    renderer.render(exchange, response);
  }

  private Response invoke(int idx, Request request, RequestHandler handler) {
    if (idx == interceptors.size()) {
      Response response = handler.handle(request);
      for (ResponseDecorator decorator : decorators) {
        response = decorator.decorate(request, response);
      }
      return response;
    }
    return interceptors.get(idx).around(request, () -> invoke(idx + 1, request, handler));
  }
```

The change: the `for (ResponseDecorator ...)` loop moved from `handle` into the `idx == interceptors.size()` branch of `invoke`. Nothing else in the file changes.

- [ ] **Step 2: Run the previously-failing test and confirm it PASSES**

Run:

```bash
mvn test -Dtest=DispatchHandlerTest#decoratorSeesInterceptorBoundScopedValue
```

Expected: PASS.

- [ ] **Step 3: Run the whole `DispatchHandlerTest` class**

Run:

```bash
mvn test -Dtest=DispatchHandlerTest
```

Expected: all tests PASS — pre-existing `invokesRegisteredHandler` should still pass; the new test passes.

- [ ] **Step 4: Commit the fix**

```bash
git add src/main/java/com/retailsvc/http/internal/DispatchHandler.java
git commit -m "fix: Run response decorators inside interceptor ScopedValue scope"
```

---

## Task 3: Pin interceptor-observes-decorated-response

**Files:**
- Modify: `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`

- [ ] **Step 1: Add the test**

Append to the class:

```java
  @Test
  void interceptorObservesDecoratedResponse() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    AtomicReference<Response> seen = new AtomicReference<>();
    RequestInterceptor capture =
        (request, next) -> {
          Response r = next.proceed();
          seen.set(r);
          return r;
        };
    ResponseDecorator stamp = (req, resp) -> resp.withHeader("X-Stamped", "yes");

    HttpExchange ex = stubExchange();
    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(capture), List.of(stamp)).handle(ex);
          return null;
        });

    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().headers()).containsEntry("X-Stamped", "yes");
  }
```

- [ ] **Step 2: Run the test**

```bash
mvn test -Dtest=DispatchHandlerTest#interceptorObservesDecoratedResponse
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java
git commit -m "test: Interceptor observes decorated response"
```

---

## Task 4: Pin decorator-throw-is-catchable-by-interceptor

Adds `import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;` alongside the existing `HTTP_OK` static import (memory: `feedback_http_status_constants` — no magic numbers).

**Files:**
- Modify: `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`

- [ ] **Step 1: Add the test**

Append to the class:

```java
  @Test
  void interceptorCanCatchDecoratorFailure() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    AtomicBoolean caught = new AtomicBoolean(false);
    RequestInterceptor catcher =
        (request, next) -> {
          try {
            return next.proceed();
          } catch (RuntimeException e) {
            caught.set(true);
            return Response.status(HTTP_INTERNAL_ERROR);
          }
        };
    ResponseDecorator boom =
        (req, resp) -> {
          throw new IllegalStateException("boom");
        };

    HttpExchange ex = stubExchange();
    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(catcher), List.of(boom)).handle(ex);
          return null;
        });

    assertThat(caught.get()).isTrue();
  }
```

- [ ] **Step 2: Run the test**

```bash
mvn test -Dtest=DispatchHandlerTest#interceptorCanCatchDecoratorFailure
```

Expected: PASS. (Before the Task 2 fix this would have failed because the decorator exception propagated past the already-popped interceptor frame straight to `ExceptionFilter`; after the fix the throw happens *inside* the interceptor's `try`.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java
git commit -m "test: Interceptor can catch decorator failures"
```

---

## Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit suite**

```bash
mvn test
```

Expected: BUILD SUCCESS, zero failures, zero errors. No flakiness expected — all new tests are synchronous and use the existing `withRequest` ScopedValue harness.

- [ ] **Step 2: Run the integration suite**

```bash
mvn verify
```

Expected: BUILD SUCCESS. This runs Failsafe IT tests including `DecoratorAndInterceptorIT`, which exercises the same code path end-to-end through `HttpServer`. Confirms no regression in the existing decorator + interceptor IT.

- [ ] **Step 3: Inspect the JaCoCo report for `DispatchHandler`**

Open `target/site/jacoco/com.retailsvc.http.internal/DispatchHandler.html`. Confirm both branches of `invoke` (`idx == interceptors.size()` and the recursive case) are covered and the new decorator loop inside the base case is exercised. If a branch is uncovered, add a test before continuing.

- [ ] **Step 4: SonarLint pre-push check**

Per project memory `feedback_sonar_pre_push`, analyze every touched file with SonarLint MCP before pushing. The worktree caveat from `feedback_sonarlint_blind_to_worktrees` applies — SonarLint may return `not_found` for files only in the worktree. If so, rely on the CI scan for this branch and continue; do not skip the local check on files that *are* visible.

Run SonarLint MCP against:
- `src/main/java/com/retailsvc/http/internal/DispatchHandler.java`
- `src/test/java/com/retailsvc/http/internal/DispatchHandlerTest.java`

Fix any new issues in this branch before pushing.

- [ ] **Step 5: Push and open PR**

```bash
git push -u origin fix/decorator-scoped-values
gh pr create --title "fix: Run response decorators inside interceptor ScopedValue scope" --body "$(cat <<'EOF'
## Summary
- Move the response-decorator loop into the base case of `DispatchHandler.invoke(...)` so decorators execute inside every `RequestInterceptor`'s `ScopedValue` scope. Honors the contract documented in README "Combining the two".
- Interceptors observe the decorated `Response` on the way back up the stack.
- A decorator exception now propagates through the interceptor chain (interceptors can catch it) before reaching `ExceptionFilter`.

Spec: `docs/superpowers/specs/2026-05-23-decorator-scoped-value-scope-design.md`

## Test plan
- [x] New unit test: decorator reads interceptor-bound `ScopedValue`
- [x] New unit test: interceptor sees decorator-added header on the returned `Response`
- [x] New unit test: interceptor `try`/`catch` around `next.proceed()` catches a throwing decorator
- [x] `mvn verify` passes (including `DecoratorAndInterceptorIT`)
EOF
)"
```

---

## Notes for the implementer

- **Do not touch `RequestPreparationFilter` or `AfterResponseHook`.** The spec explicitly scopes those out. Fixing after-hooks to see interceptor ScopedValues is a separate, larger change with a contract shift.
- **Do not edit `README.md`.** The README is already correct; this PR brings the implementation into compliance.
- **Decorator order is preserved.** They still iterate in registration order; that was true before and is still true after the move.
- **Branch is `fix/decorator-scoped-values`** (already created via worktree).
- **Commit subjects start with a capital letter after the type prefix** — see memory `feedback_skip_commitlint_in_worktrees`.
