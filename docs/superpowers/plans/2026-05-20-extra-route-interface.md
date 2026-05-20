# Extra-route interface, ExceptionHandler decoupling, BadRequestException Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove every `com.sun.net.httpserver` import from `com.retailsvc.http.*` (excluding `com.retailsvc.http.internal.*`), introduce `BadRequestException`, and serialize problem+json via the registered JSON `TypeMapper` instead of the hand-rolled `ProblemDetailRenderer`.

**Architecture:** `extraRoute` accepts a `RequestHandler` (same triple as OpenAPI operations); an internal `ExtraRouteAdapter` bridges to the JDK `HttpHandler`. `ExceptionHandler.handle(Throwable)` returns a `Response`, rendered by `ResponseRenderer`. The default handler is built with the resolved JSON `TypeMapper` and serializes a `ProblemDetail` record. `Request.method()` exposes the HTTP method via the existing `com.retailsvc.http.spec.HttpMethod` enum.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito, Maven (Surefire/Failsafe), Google Java Format.

**Spec:** `docs/superpowers/specs/2026-05-20-extra-route-interface-design.md`

**Worktree:** Work in `.claude/worktrees/extra-route-interface` on branch `feat/extra-route-interface`. Commit messages: skip commitlint via `SKIP=commitlint git commit ...`; capitalise the first character after the conventional-commit `:`.

---

## File Structure

**New files (main):**
- `src/main/java/com/retailsvc/http/BadRequestException.java` — public 4xx exception.
- `src/main/java/com/retailsvc/http/internal/ExtraRouteAdapter.java` — bridges JDK `HttpHandler` to `RequestHandler` for extras.
- `src/main/java/com/retailsvc/http/internal/ProblemDetail.java` — record serialized by the registered JSON `TypeMapper`.
- `src/main/java/com/retailsvc/http/internal/NotFoundHandler.java` — moved from `Handlers.notFoundHandler()`.

**Modified files (main):**
- `src/main/java/com/retailsvc/http/Request.java` — adds `HttpMethod method` field/getter; updates constructors and `withPrincipals`.
- `src/main/java/com/retailsvc/http/ExceptionHandler.java` — signature becomes `Response handle(Throwable)`.
- `src/main/java/com/retailsvc/http/Handlers.java` — factories return `RequestHandler`; `defaultExceptionHandler(TypeMapper)`; `notFoundHandler` removed.
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — `extraRoute(String, RequestHandler)`; wires `defaultExceptionHandler(jsonMapper)`; uses `ExtraRouteAdapter`; uses `internal/NotFoundHandler`.
- `src/main/java/com/retailsvc/http/internal/ExceptionFilter.java` — takes `ResponseRenderer`; renders the returned `Response`.
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — passes `method` to `Request`.

**Deleted files (main):**
- `src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java`
- `src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java`

**Test files (modified/new):**
- `src/test/java/com/retailsvc/http/BadRequestExceptionTest.java` (new)
- `src/test/java/com/retailsvc/http/RequestTest.java` (constructor signature updates)
- `src/test/java/com/retailsvc/http/HandlersTest.java` (rewritten to use `RequestHandler`)
- `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java` (rewritten to assert `Response` outputs)
- `src/test/java/com/retailsvc/http/ExtraHandlersIT.java` (signature migration, exception-flow test uses `RequestHandler`)
- `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java` (signature migration)
- `src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java` — delete (renderer is gone)
- `src/test/java/com/retailsvc/http/internal/ExtraRouteAdapterTest.java` (new unit test for adapter)

---

## Task 1: Add `HttpMethod method` field to `Request`

Make method observable on `Request`. Every existing construction site moves from the 7/8-arg form to an 8/9-arg form taking `HttpMethod`. Done first because every subsequent task depends on it.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/Request.java`
- Modify: `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`
- Modify: `src/main/java/com/retailsvc/http/internal/SecurityFilter.java` (uses `withPrincipals`)
- Test: `src/test/java/com/retailsvc/http/RequestTest.java`

- [ ] **Step 1.1: Write a failing test for `Request.method()`**

Add to `RequestTest.java`:

```java
@Test
void exposesMethod() {
  Request req =
      new Request(
          new byte[0],
          null,
          null,
          "op",
          Map.of(),
          null,
          NO_HEADERS,
          Map.of(),
          com.retailsvc.http.spec.HttpMethod.POST);

  assertThat(req.method()).isEqualTo(com.retailsvc.http.spec.HttpMethod.POST);
}
```

- [ ] **Step 1.2: Run test, expect compile failure**

Run: `mvn -q test -Dtest=RequestTest#exposesMethod`
Expected: compilation error — no 9-arg `Request` constructor, no `method()` accessor.

- [ ] **Step 1.3: Add `method` to `Request`**

Update `Request.java`:

1. Add field: `private final com.retailsvc.http.spec.HttpMethod method;`
2. Replace the two existing constructors with a single 9-arg primary constructor (keep `@SuppressWarnings("java:S107")`):

```java
@SuppressWarnings("java:S107")
public Request(
    byte[] body,
    Object parsed,
    TypeMapper bodyMapper,
    String operationId,
    Map<String, String> pathParameters,
    String rawQuery,
    UnaryOperator<String> headerLookup,
    Map<String, Object> principals,
    com.retailsvc.http.spec.HttpMethod method) {
  this.body = body;
  this.parsed = parsed;
  this.bodyMapper = bodyMapper;
  this.operationId = operationId;
  this.pathParameters = pathParameters;
  this.rawQuery = rawQuery;
  this.headerLookup = headerLookup;
  this.principals = Map.copyOf(principals);
  this.method = method;
}
```

3. Add a back-compat overload that supplies `null` principals/method for tests (keep the existing 7/8-arg signatures intact for test convenience; they delegate to the 9-arg form with `principals = Map.of()` and `method = null`):

```java
public Request(
    byte[] body,
    Object parsed,
    TypeMapper bodyMapper,
    String operationId,
    Map<String, String> pathParameters,
    String rawQuery,
    UnaryOperator<String> headerLookup) {
  this(body, parsed, bodyMapper, operationId, pathParameters, rawQuery, headerLookup, Map.of(), null);
}

@SuppressWarnings("java:S107")
public Request(
    byte[] body,
    Object parsed,
    TypeMapper bodyMapper,
    String operationId,
    Map<String, String> pathParameters,
    String rawQuery,
    UnaryOperator<String> headerLookup,
    Map<String, Object> principals) {
  this(body, parsed, bodyMapper, operationId, pathParameters, rawQuery, headerLookup, principals, null);
}
```

4. Add accessor:

```java
public com.retailsvc.http.spec.HttpMethod method() {
  return method;
}
```

5. Thread `method` through `withPrincipals`:

```java
public Request withPrincipals(Map<String, Object> principals) {
  return new Request(
      body, parsed, bodyMapper, operationId, pathParameters, rawQuery, headerLookup, principals, method);
}
```

- [ ] **Step 1.4: Update `RequestPreparationFilter` to pass method**

In `RequestPreparationFilter.java` around line 68, replace the `new Request(...)` call so it passes `method` as the 9th argument:

```java
Request request =
    new Request(
        body,
        parsedBody.value(),
        parsedBody.mapper(),
        op.operationId(),
        match.pathParameters(),
        exchange.getRequestURI().getRawQuery(),
        headers::getFirst,
        Map.of(),
        method);
```

- [ ] **Step 1.5: Run the new test and the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, 377 tests pass (existing 376 + new `exposesMethod`).

If `SecurityFilter` or other callers fail to compile, they are using `withPrincipals` only — that already threads `method` through and should still compile. If a test fails because `method()` returns `null` for a `Request` constructed via the 7/8-arg overload, that's expected — those tests don't exercise method.

- [ ] **Step 1.6: Commit**

```bash
git add src/main/java/com/retailsvc/http/Request.java \
        src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java \
        src/test/java/com/retailsvc/http/RequestTest.java
SKIP=commitlint git commit -m "feat: Expose HTTP method on Request"
```

---

## Task 2: Add `BadRequestException`

Public exception users throw from handlers to surface 4xx errors with optional pointer/keyword.

**Files:**
- Create: `src/main/java/com/retailsvc/http/BadRequestException.java`
- Test: `src/test/java/com/retailsvc/http/BadRequestExceptionTest.java`

- [ ] **Step 2.1: Write failing test**

`src/test/java/com/retailsvc/http/BadRequestExceptionTest.java`:

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BadRequestExceptionTest {

  @Test
  void defaultsStatusTo400() {
    BadRequestException e = new BadRequestException("bad input");

    assertThat(e.status()).isEqualTo(400);
    assertThat(e.getMessage()).isEqualTo("bad input");
    assertThat(e.pointer()).isEmpty();
    assertThat(e.keyword()).isEmpty();
  }

  @Test
  void honorsExplicitStatus() {
    BadRequestException e = new BadRequestException(422, "email taken");

    assertThat(e.status()).isEqualTo(422);
    assertThat(e.getMessage()).isEqualTo("email taken");
  }

  @Test
  void carriesPointerAndKeyword() {
    BadRequestException e = new BadRequestException(422, "email taken", "/email", "unique");

    assertThat(e.pointer()).contains("/email");
    assertThat(e.keyword()).contains("unique");
  }

  @Test
  void rejectsNon4xxStatus() {
    assertThatThrownBy(() -> new BadRequestException(500, "boom"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4xx");
    assertThatThrownBy(() -> new BadRequestException(399, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4xx");
  }

  @Test
  void rejectsNullDetail() {
    assertThatThrownBy(() -> new BadRequestException(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("detail");
  }
}
```

- [ ] **Step 2.2: Run test, expect compile failure**

Run: `mvn -q test -Dtest=BadRequestExceptionTest`
Expected: compilation error — `BadRequestException` does not exist.

- [ ] **Step 2.3: Implement `BadRequestException`**

`src/main/java/com/retailsvc/http/BadRequestException.java`:

```java
package com.retailsvc.http;

import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by user handlers to signal a 4xx client error. The default {@link ExceptionHandler}
 * renders this as an RFC 7807 {@code application/problem+json} response carrying the supplied
 * status, detail, and optional JSON-pointer / validation-keyword fields.
 *
 * <p>Use for cases like {@code 422 Unprocessable Content} (payload is syntactically valid but
 * violates a business rule), {@code 409 Conflict}, {@code 412 Precondition Failed}, etc. For
 * 5xx errors, throw an ordinary {@link RuntimeException} and let the default handler render 500.
 */
public final class BadRequestException extends RuntimeException {

  private static final int DEFAULT_STATUS = 400;

  private final int status;
  private final String pointer;
  private final String keyword;

  public BadRequestException(String detail) {
    this(DEFAULT_STATUS, detail, null, null);
  }

  public BadRequestException(int status, String detail) {
    this(status, detail, null, null);
  }

  public BadRequestException(int status, String detail, String pointer, String keyword) {
    super(Objects.requireNonNull(detail, "detail must not be null"));
    if (status < 400 || status > 499) {
      throw new IllegalArgumentException("status must be 4xx, got " + status);
    }
    this.status = status;
    this.pointer = pointer;
    this.keyword = keyword;
  }

  public int status() {
    return status;
  }

  public Optional<String> pointer() {
    return Optional.ofNullable(pointer);
  }

  public Optional<String> keyword() {
    return Optional.ofNullable(keyword);
  }
}
```

- [ ] **Step 2.4: Run test, expect pass**

Run: `mvn -q test -Dtest=BadRequestExceptionTest`
Expected: BUILD SUCCESS, 5 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add src/main/java/com/retailsvc/http/BadRequestException.java \
        src/test/java/com/retailsvc/http/BadRequestExceptionTest.java
SKIP=commitlint git commit -m "feat: Add BadRequestException for 4xx handler errors"
```

---

## Task 3: Add internal `ProblemDetail` record

Data carrier for RFC 7807 problem+json bodies. Serialized later (Task 4) via the registered JSON `TypeMapper`.

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ProblemDetail.java`

- [ ] **Step 3.1: Write `ProblemDetail`**

`src/main/java/com/retailsvc/http/internal/ProblemDetail.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.validate.ValidationError;
import java.util.Map;

/**
 * Carrier for an RFC 7807 problem+json document. Serialized by the registered JSON
 * {@code TypeMapper}; the wire shape and field-order are whatever the configured mapper
 * produces — title is advisory per RFC 7807 since {@code type} is always {@code about:blank}.
 */
public record ProblemDetail(
    String type, String title, int status, String detail, String pointer, String keyword) {

  private static final String DEFAULT_TYPE = "about:blank";

  public static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(DEFAULT_TYPE, "Bad Request", 400, e.message(), e.pointer(), e.keyword());
  }

  public static ProblemDetail forBadRequest(BadRequestException e) {
    return new ProblemDetail(
        DEFAULT_TYPE,
        titleFor(e.status()),
        e.status(),
        e.getMessage(),
        e.pointer().orElse(null),
        e.keyword().orElse(null));
  }

  private static final Map<Integer, String> TITLES =
      Map.of(
          400, "Bad Request",
          401, "Unauthorized",
          403, "Forbidden",
          404, "Not Found",
          405, "Method Not Allowed",
          409, "Conflict",
          410, "Gone",
          412, "Precondition Failed",
          415, "Unsupported Media Type",
          422, "Unprocessable Content");

  private static String titleFor(int status) {
    return TITLES.getOrDefault(status, "Bad Request");
  }
}
```

- [ ] **Step 3.2: Compile-check**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3.3: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ProblemDetail.java
SKIP=commitlint git commit -m "feat: Add internal ProblemDetail record for problem+json bodies"
```

---

## Task 4: Migrate `ExceptionHandler` to `Throwable -> Response`

Change the public interface, render the returned `Response` through `ResponseRenderer`, and rewrite the default handler to use `ProblemDetail` + the registered JSON `TypeMapper`. Wire the JSON mapper from `OpenApiServer.Builder.build()`.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/ExceptionHandler.java`
- Modify: `src/main/java/com/retailsvc/http/internal/ExceptionFilter.java`
- Modify: `src/main/java/com/retailsvc/http/Handlers.java` (only `defaultExceptionHandler`)
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`
- Modify: `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java`

- [ ] **Step 4.1: Change `ExceptionHandler` signature**

Replace `src/main/java/com/retailsvc/http/ExceptionHandler.java` with:

```java
package com.retailsvc.http;

/**
 * Maps a {@link Throwable} thrown anywhere in the request pipeline to a {@link Response}.
 *
 * <p>Runs outside any {@code ScopedValue} bindings established by filters or interceptors —
 * scopes are torn down as the exception unwinds. Context-aware error mapping (trace IDs, etc.)
 * should be done in a {@link RequestInterceptor} that wraps {@code next.proceed()} in try/catch.
 */
@FunctionalInterface
public interface ExceptionHandler {

  Response handle(Throwable t);
}
```

- [ ] **Step 4.2: Update `ExceptionFilter` to render via `ResponseRenderer`**

Replace `src/main/java/com/retailsvc/http/internal/ExceptionFilter.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Response;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class ExceptionFilter extends Filter {

  private final ExceptionHandler handler;
  private final ResponseRenderer renderer;

  public ExceptionFilter(ExceptionHandler handler, ResponseRenderer renderer) {
    this.handler = handler;
    this.renderer = renderer;
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      Response response = handler.handle(t);
      renderer.render(exchange, response);
    }
  }

  @Override
  public String description() {
    return "Exception filter";
  }
}
```

- [ ] **Step 4.3: Rewrite `Handlers.defaultExceptionHandler`**

In `Handlers.java`:

1. Add imports:

```java
import com.retailsvc.http.internal.ProblemDetail;
import java.util.stream.Collectors;
```

Remove the now-unused `ProblemDetailRenderer` import.

2. Replace the existing `defaultExceptionHandler()` body. The old method returned an `ExceptionHandler` that wrote to `HttpExchange`. The new one takes `TypeMapper jsonMapper` and returns a `Throwable -> Response`:

```java
public static ExceptionHandler defaultExceptionHandler(TypeMapper jsonMapper) {
  Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
  return t ->
      switch (t) {
        case ValidationException ve ->
            Response.bytes(
                HTTP_BAD_REQUEST,
                jsonMapper.writeTo(ProblemDetail.forValidation(ve.error())),
                "application/problem+json");
        case BadRequestException bre ->
            Response.bytes(
                bre.status(),
                jsonMapper.writeTo(ProblemDetail.forBadRequest(bre)),
                "application/problem+json");
        case NotFoundException _ -> Response.notFound();
        case MethodNotAllowedException mna ->
            Response.status(HTTP_BAD_METHOD)
                .withHeader(
                    "Allow",
                    mna.allowed().stream().map(Enum::name).collect(Collectors.joining(", ")));
        default -> {
          LOG.error("Unhandled exception in handler", t);
          yield Response.status(HTTP_INTERNAL_ERROR);
        }
      };
}
```

Imports needed in `Handlers.java`: `HTTP_BAD_REQUEST`, `HTTP_BAD_METHOD`, `HTTP_INTERNAL_ERROR` from `java.net.HttpURLConnection` (some already present).

Delete the no-arg `defaultExceptionHandler()` overload — every caller passes a JSON mapper now (the framework wires it; users who supply their own custom `ExceptionHandler` skip this entirely).

- [ ] **Step 4.4: Wire JSON mapper in `OpenApiServer.Builder.build()`**

In `OpenApiServer.java`, locate the `build()` method (around line 271). Replace the section that resolves the exception handler. The current code is in the constructor (around line 74); change it so the resolved JSON mapper is available when constructing the default handler.

Updated `build()` (relevant block):

```java
Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
ExceptionHandler effectiveExceptionHandler =
    exceptionHandler != null
        ? exceptionHandler
        : Handlers.defaultExceptionHandler(resolved.get(JSON));
HandlerConfig handlerConfig =
    new HandlerConfig(
        handlers,
        interceptors,
        decorators,
        effectiveExceptionHandler,
        extras,
        Map.copyOf(securityValidators),
        externalAuth);
return new OpenApiServer(spec, resolved, handlerConfig, port, shutdownTimeoutSeconds);
```

Remove the in-constructor fallback block:

```java
ExceptionHandler exceptionHandler = handlerConfig.exceptionHandler();
if (exceptionHandler == null) {
  LOG.warn("No ExceptionHandler set, using default");
  exceptionHandler = Handlers.defaultExceptionHandler();
}
```

Replace with: `ExceptionHandler exceptionHandler = handlerConfig.exceptionHandler();` (already non-null by construction).

- [ ] **Step 4.5: Update `ExceptionFilter` construction**

In `OpenApiServer.java`, the two `new ExceptionFilter(exceptionHandler)` calls (around lines 91 and 110) now need a `ResponseRenderer`. Construct one `ResponseRenderer` early in the constructor and reuse it:

```java
ResponseRenderer renderer = new ResponseRenderer(bodyMappers);
// ...
ctx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
// ...
extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
```

The existing `new ResponseRenderer(bodyMappers)` argument to `DispatchHandler` (around line 106) becomes `renderer`. Single renderer instance shared by `ExceptionFilter` and `DispatchHandler`.

- [ ] **Step 4.6: Rewrite `HandlersDefaultExceptionTest`**

Replace `src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java`:

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.validate.ValidationError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HandlersDefaultExceptionTest {

  private static final TypeMapper JSON =
      new GsonTypeMapper(); // built-in Gson mapper covers serialization

  @Test
  void validationExceptionRendersProblemJson() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(new ValidationException(new ValidationError("/x", "type", "expected string", null)));

    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    byte[] bytes = (byte[]) resp.body();
    String json = new String(bytes, StandardCharsets.UTF_8);
    Map<?, ?> parsed = (Map<?, ?>) JSON.readFrom(bytes, "application/json");
    assertThat(parsed).containsEntry("status", 400.0).containsEntry("keyword", "type");
    assertThat(json).contains("expected string");
  }

  @Test
  void badRequestExceptionRendersProblemJsonWithCustomStatus() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(new BadRequestException(422, "email taken", "/email", "unique"));

    assertThat(resp.status()).isEqualTo(422);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    Map<?, ?> parsed = (Map<?, ?>) JSON.readFrom((byte[]) resp.body(), "application/json");
    assertThat(parsed)
        .containsEntry("status", 422.0)
        .containsEntry("title", "Unprocessable Content")
        .containsEntry("detail", "email taken")
        .containsEntry("pointer", "/email")
        .containsEntry("keyword", "unique");
  }

  @Test
  void notFoundReturns404() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON).handle(new NotFoundException("GET /x"));

    assertThat(resp.status()).isEqualTo(404);
    assertThat(resp.body()).isNull();
  }

  @Test
  void methodNotAllowedReturns405WithAllowHeader() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST)));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsKey("Allow");
    assertThat(resp.headers().get("Allow")).contains("GET").contains("POST");
  }

  @Test
  void unknownExceptionReturns500() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON).handle(new RuntimeException("kaboom"));

    assertThat(resp.status()).isEqualTo(500);
    assertThat(resp.body()).isNull();
  }
}
```

Note: Gson deserializes numeric JSON as `Double`, hence `400.0`.

- [ ] **Step 4.7: Run the suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS. `HandlersDefaultExceptionTest` passes (5 tests). Other tests that called the old `Handlers.defaultExceptionHandler()` no-arg form will fail to compile — that's expected and addressed in Task 6 (`ExtraHandlersIT`) and Task 7 (`OpenApiServerBuilderTest`). If failures appear only in those files, proceed; otherwise resolve before committing.

For now, temporarily comment out broken test usages OR use the workaround: every test that currently calls `defaultExceptionHandler()` should pass through the builder which auto-wires; explicit calls in tests can pass `new GsonTypeMapper()`. Update the obvious affected tests inline now and re-run:

- `ExtraHandlersIT.java` lines 20, 44, 74: change `.exceptionHandler(defaultExceptionHandler())` to `.exceptionHandler(defaultExceptionHandler(new GsonTypeMapper()))`. Update the static import accordingly.

Run again: `mvn -q test`
Expected: BUILD SUCCESS for unit tests (IT not run here).

- [ ] **Step 4.8: Commit**

```bash
git add src/main/java/com/retailsvc/http/ExceptionHandler.java \
        src/main/java/com/retailsvc/http/Handlers.java \
        src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/main/java/com/retailsvc/http/internal/ExceptionFilter.java \
        src/test/java/com/retailsvc/http/HandlersDefaultExceptionTest.java \
        src/test/java/com/retailsvc/http/ExtraHandlersIT.java
SKIP=commitlint git commit -m "feat: ExceptionHandler returns Response; problem+json via TypeMapper"
```

---

## Task 5: Migrate `Handlers.aliveHandler/healthHandler/specHandler` to `RequestHandler`

Inline the 405-on-non-GET/HEAD check; produce `Response` directly. Delete `MethodLimitedHandler` after no callers remain.

**Files:**
- Modify: `src/main/java/com/retailsvc/http/Handlers.java`
- Modify: `src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java` → becomes a plain bytes/content-type pair, not an `HttpHandler`.
- Test: `src/test/java/com/retailsvc/http/HandlersTest.java` (rewritten)
- Test: `src/test/java/com/retailsvc/http/HealthHandlerTest.java` (signature update — verify and adjust)

- [ ] **Step 5.1: Rewrite `HandlersTest`**

Replace `src/test/java/com/retailsvc/http/HandlersTest.java`:

```java
package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HandlersTest {

  private static final UnaryOperator<String> NO_HEADERS = name -> null;

  private static Request request(HttpMethod method) {
    return new Request(new byte[0], null, null, null, Map.of(), null, NO_HEADERS, Map.of(), method);
  }

  @Test
  void aliveHandlerReturns204OnGet() {
    Response resp = Handlers.aliveHandler().handle(request(GET));

    assertThat(resp.status()).isEqualTo(204);
    assertThat(resp.body()).isNull();
  }

  @Test
  void aliveHandlerReturns204OnHead() {
    Response resp = Handlers.aliveHandler().handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(204);
  }

  @Test
  void aliveHandlerReturns405OnPost() {
    Response resp = Handlers.aliveHandler().handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void specHandlerServesYamlBytesWithInferredContentType() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(GET));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.contentType()).isEqualTo("application/yaml");
    assertThat(resp.body()).isInstanceOf(byte[].class);
    assertThat((byte[]) resp.body()).isNotEmpty();
  }

  @Test
  void specHandlerInfersJsonContentType() {
    Response resp = Handlers.specHandler("/openapi.json").handle(request(GET));

    assertThat(resp.contentType()).isEqualTo("application/json");
  }

  @Test
  void specHandlerThrowsAtConstructionForMissingResource() {
    assertThatThrownBy(() -> Handlers.specHandler("/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.yaml");
  }

  @Test
  void specHandlerReturns405OnPost() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void specHandlerHeadReturnsContentLengthWithoutBody() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers()).containsKey("Content-Length");
    assertThat(Integer.parseInt(resp.headers().get("Content-Length"))).isGreaterThan(0);
  }
}
```

- [ ] **Step 5.2: Run, expect compile failure**

Run: `mvn -q test -Dtest=HandlersTest`
Expected: compilation error — return types don't match.

- [ ] **Step 5.3: Migrate `Handlers.aliveHandler`**

In `Handlers.java`, replace the existing `aliveHandler()`:

```java
/** Returns 204 No Content on GET/HEAD; 405 with {@code Allow: GET, HEAD} otherwise. */
public static RequestHandler aliveHandler() {
  return req ->
      switch (req.method()) {
        case GET, HEAD -> Response.empty();
        default -> Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
      };
}
```

Add static import: `import static com.retailsvc.http.spec.HttpMethod.GET;` and `HEAD`. Or qualify inline — the agent picks whichever fits the existing import style (file uses static imports for HTTP constants; follow that).

- [ ] **Step 5.4: Migrate `Handlers.specHandler`**

`ClasspathResourceHandler` currently implements `HttpHandler`. Refactor it to a plain immutable holder of `(bytes, contentType)`. Replace `src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java`:

```java
package com.retailsvc.http.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Eagerly-loaded bytes for a classpath resource. Content-Type is inferred from the file
 * extension. Throws {@link IllegalArgumentException} if the resource is missing.
 */
public final class ClasspathResourceHandler {

  private final byte[] bytes;
  private final String contentType;

  public ClasspathResourceHandler(String classpathResource) {
    try (InputStream in = ClasspathResourceHandler.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
      }
      this.bytes = in.readAllBytes();
    } catch (IOException io) {
      throw new IllegalArgumentException(
          "failed reading classpath resource: " + classpathResource, io);
    }
    this.contentType = contentTypeFor(classpathResource);
  }

  public byte[] bytes() {
    return bytes;
  }

  public String contentType() {
    return contentType;
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

Replace `Handlers.specHandler`:

```java
public static RequestHandler specHandler(String classpathResource) {
  ClasspathResourceHandler resource = new ClasspathResourceHandler(classpathResource);
  byte[] bytes = resource.bytes();
  String contentType = resource.contentType();
  return req ->
      switch (req.method()) {
        case GET -> Response.bytes(HTTP_OK, bytes, contentType);
        case HEAD ->
            Response.status(HTTP_OK)
                .withContentType(contentType)
                .withHeader("Content-Length", String.valueOf(bytes.length));
        default -> Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
      };
}
```

- [ ] **Step 5.5: Migrate `Handlers.healthHandler`**

Replace `Handlers.healthHandler(TypeMapper, Supplier<HealthOutcome>)`:

```java
public static RequestHandler healthHandler(TypeMapper jsonMapper, Supplier<HealthOutcome> probe) {
  Objects.requireNonNull(jsonMapper, "jsonMapper");
  Objects.requireNonNull(probe, "probe");
  return req -> {
    if (req.method() != GET && req.method() != HEAD) {
      return Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
    }
    HealthOutcome outcome;
    try {
      outcome = Objects.requireNonNull(probe.get(), "Health probe returned null");
    } catch (RuntimeException e) {
      LOG.warn("Health probe failed", e);
      outcome = new HealthOutcome(false, List.of());
    }
    byte[] body = jsonMapper.writeTo(toWireShape(outcome));
    int status = outcome.up() ? HTTP_OK : HTTP_UNAVAILABLE;
    return Response.bytes(status, body, "application/json");
  };
}
```

`toWireShape`, `label`, `HealthBody`, `DependencyBody` stay as-is.

- [ ] **Step 5.6: Update `HealthHandlerTest`**

Note: `Handlers.notFoundHandler()` is left in place this task and removed in Task 6 alongside introducing `internal/NotFoundHandler` (single atomic switch so the catch-all `/` context never references a missing symbol).



Read `src/test/java/com/retailsvc/http/HealthHandlerTest.java` and adapt: every `HttpExchange` mock is replaced with `Request` construction (see `HandlersTest` pattern in Step 5.1); assertions read `Response.status()`, `Response.contentType()`, `Response.body()` instead of `verify(ex).sendResponseHeaders(...)`.

This file is not enumerated here line-by-line — apply the same mechanical translation as `HandlersTest`.

- [ ] **Step 5.7: Run all unit tests**

Run: `mvn -q test`
Expected: BUILD SUCCESS. If any test file other than `OpenApiServerBuilderTest`, `ExtraHandlersIT` still fails, fix it inline before committing — those two are addressed next.

- [ ] **Step 5.8: Commit**

```bash
git add src/main/java/com/retailsvc/http/Handlers.java \
        src/main/java/com/retailsvc/http/internal/ClasspathResourceHandler.java \
        src/test/java/com/retailsvc/http/HandlersTest.java \
        src/test/java/com/retailsvc/http/HealthHandlerTest.java
SKIP=commitlint git commit -m "feat: Migrate Handlers factories to RequestHandler"
```

---

## Task 6: `extraRoute(String, RequestHandler)` + `ExtraRouteAdapter`

Switch the builder to the new signature, introduce the internal adapter, delete `MethodLimitedHandler`, and migrate `Handlers.notFoundHandler` to `internal/NotFoundHandler`.

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ExtraRouteAdapter.java`
- Create: `src/main/java/com/retailsvc/http/internal/NotFoundHandler.java`
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`
- Delete: `src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java`
- Test: `src/test/java/com/retailsvc/http/internal/ExtraRouteAdapterTest.java` (new)
- Modify: `src/test/java/com/retailsvc/http/ExtraHandlersIT.java`
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerBuilderTest.java`

- [ ] **Step 6.1: Write failing unit test for `ExtraRouteAdapter`**

`src/test/java/com/retailsvc/http/internal/ExtraRouteAdapterTest.java`:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.GsonTypeMapper;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtraRouteAdapterTest {

  @Test
  void buildsRequestWithMethodQueryHeadersAndBodyBytesAndNullOperationId() throws Exception {
    AtomicReference<Request> captured = new AtomicReference<>();
    RequestHandler handler =
        req -> {
          captured.set(req);
          return Response.empty();
        };

    Map<String, TypeMapper> mappers = Map.of("application/json", new GsonTypeMapper());
    ResponseRenderer renderer = new ResponseRenderer(mappers);
    ExtraRouteAdapter adapter = new ExtraRouteAdapter(handler, renderer);

    HttpExchange ex = mock(HttpExchange.class);
    Headers reqHeaders = new Headers();
    reqHeaders.add("X-Trace", "abc");
    when(ex.getRequestMethod()).thenReturn("POST");
    when(ex.getRequestURI()).thenReturn(new URI("/alive?x=1"));
    when(ex.getRequestHeaders()).thenReturn(reqHeaders);
    when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream("hi".getBytes()));
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    adapter.handle(ex);

    Request r = captured.get();
    assertThat(r.operationId()).isNull();
    assertThat(r.pathParams()).isEmpty();
    assertThat(r.principals()).isEmpty();
    assertThat(r.method()).isEqualTo(com.retailsvc.http.spec.HttpMethod.POST);
    assertThat(r.rawQuery()).isEqualTo("x=1");
    assertThat(r.header("X-Trace")).contains("abc");
    assertThat(r.bytes()).containsExactly('h', 'i');
  }
}
```

- [ ] **Step 6.2: Run, expect compile failure**

Run: `mvn -q test -Dtest=ExtraRouteAdapterTest`
Expected: `ExtraRouteAdapter` not found.

- [ ] **Step 6.3: Implement `ExtraRouteAdapter`**

`src/main/java/com/retailsvc/http/internal/ExtraRouteAdapter.java`:

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.HttpMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Bridges an extra-route {@link RequestHandler} to the underlying JDK {@link HttpHandler}.
 *
 * <p>Builds a {@link Request} with {@code operationId=null}, empty path-params, empty principals,
 * raw body bytes, raw query, and the parsed HTTP method, then renders the returned {@link
 * Response} through the shared {@link ResponseRenderer}. OpenAPI validation, body parsing, and
 * security are intentionally bypassed — extras are by definition outside the spec.
 */
public final class ExtraRouteAdapter implements HttpHandler {

  private final RequestHandler handler;
  private final ResponseRenderer renderer;

  public ExtraRouteAdapter(RequestHandler handler, ResponseRenderer renderer) {
    this.handler = handler;
    this.renderer = renderer;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    var headers = exchange.getRequestHeaders();
    Request request =
        new Request(
            body,
            null,
            null,
            null,
            Map.of(),
            exchange.getRequestURI().getRawQuery(),
            headers::getFirst,
            Map.of(),
            method);
    Response response = handler.handle(request);
    renderer.render(exchange, response);
  }
}
```

- [ ] **Step 6.4: Run adapter test, expect pass**

Run: `mvn -q test -Dtest=ExtraRouteAdapterTest`
Expected: BUILD SUCCESS.

- [ ] **Step 6.5: Create `internal/NotFoundHandler`**

`src/main/java/com/retailsvc/http/internal/NotFoundHandler.java`:

```java
package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Returns 404 with no body. Used for the framework's catch-all {@code /} context. */
public final class NotFoundHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1);
    }
  }
}
```

- [ ] **Step 6.6: Switch `OpenApiServer.Builder.extraRoute` to `RequestHandler`**

In `OpenApiServer.java`:

1. Change field declaration:

```java
private final LinkedHashMap<String, RequestHandler> extras = new LinkedHashMap<>();
```

2. Replace `extraRoute(String, HttpHandler)` with:

```java
public Builder extraRoute(String path, RequestHandler handler) {
  requireNonNull(path, "path must not be null");
  requireNonNull(handler, "handler must not be null");
  if (extras.containsKey(path)) {
    throw new IllegalStateException("duplicate extra route path: " + path);
  }
  extras.put(path, handler);
  return this;
}
```

3. Update `HandlerConfig`:

```java
record HandlerConfig(
    Map<String, RequestHandler> handlers,
    List<RequestInterceptor> interceptors,
    List<ResponseDecorator> decorators,
    ExceptionHandler exceptionHandler,
    Map<String, RequestHandler> extras,
    Map<String, SchemeValidator> securityValidators,
    boolean externalAuth) {}
```

4. Update the extras-wiring loop in the constructor to use `ExtraRouteAdapter`:

```java
for (Map.Entry<String, RequestHandler> e : handlerConfig.extras().entrySet()) {
  HttpContext extraCtx = httpServer.createContext(e.getKey());
  extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
  extraCtx.setHandler(new ExtraRouteAdapter(e.getValue(), renderer));
}
```

5. Replace the catch-all:

```java
httpServer.createContext("/", new com.retailsvc.http.internal.NotFoundHandler());
```

6. Delete `Handlers.notFoundHandler()` (now unused).

7. Remove the `import com.sun.net.httpserver.HttpHandler;` from `OpenApiServer.java` if no longer needed.

- [ ] **Step 6.7: Update `ExtraHandlersIT`**

`extraHandlerExceptionFlowsThroughExceptionHandler` currently constructs a `com.sun.net.httpserver.HttpHandler`. Replace with `RequestHandler`:

```java
@Test
void extraHandlerExceptionFlowsThroughExceptionHandler() throws Exception {
  RequestHandler boom =
      req -> {
        throw new RuntimeException("boom");
      };

  try (var s =
          newBuilder()
              .spec(spec)
              .handlers(Map.of())
              .port(0)
              .extraRoute("/boom", boom)
              .build();
      var client = httpClient()) {

    var req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + s.listenPort() + "/boom"))
            .GET()
            .build();
    var resp = client.send(req, BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(500);
  }
}
```

Note the removed `.exceptionHandler(defaultExceptionHandler(...))` line — builder auto-wires it now. Apply the same removal to the other two tests in the file (they no longer need to set it explicitly).

- [ ] **Step 6.8: Update `OpenApiServerBuilderTest`**

Read the test, find `.extraRoute("/alive", duplicate)`. `duplicate` is currently an `HttpHandler`; change its declared/inferred type to `RequestHandler`:

```java
RequestHandler duplicate = req -> Response.empty();
```

And the `.extraRoute("/api", Handlers.aliveHandler())` line still works since `Handlers.aliveHandler()` now returns `RequestHandler`.

- [ ] **Step 6.9: Delete `MethodLimitedHandler`**

```bash
git rm src/main/java/com/retailsvc/http/internal/MethodLimitedHandler.java
```

- [ ] **Step 6.10: Run full suite including integration tests**

Run: `mvn -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6.11: Commit**

```bash
git add -A
SKIP=commitlint git commit -m "feat: ExtraRoute accepts RequestHandler via ExtraRouteAdapter"
```

---

## Task 7: Delete `ProblemDetailRenderer` and its test

The renderer has no remaining callers after Task 4.

**Files:**
- Delete: `src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java`
- Delete: `src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java` (if present)

- [ ] **Step 7.1: Confirm no callers remain**

Run: `grep -rn "ProblemDetailRenderer" src/main/java/ src/test/java/`
Expected: only the renderer file itself (and possibly its own test).

If anything else matches, that call site needs updating to use `ProblemDetail` + the registered `TypeMapper`.

- [ ] **Step 7.2: Delete the files**

```bash
git rm src/main/java/com/retailsvc/http/internal/ProblemDetailRenderer.java
[ -f src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java ] && \
  git rm src/test/java/com/retailsvc/http/internal/ProblemDetailRendererTest.java
```

- [ ] **Step 7.3: Run full suite**

Run: `mvn -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 7.4: Commit**

```bash
SKIP=commitlint git commit -m "refactor: Delete hand-rolled ProblemDetailRenderer"
```

---

## Task 8: Verify public API is free of `com.sun.net.httpserver`

Final guard that the goal is met.

- [ ] **Step 8.1: Grep**

Run:

```bash
grep -rn "com\.sun\.net\.httpserver" src/main/java/com/retailsvc/http/ \
  | grep -v "/internal/"
```

Expected: no output.

If any line is reported, fix that file (move the dependency behind an `internal/` type, or remove it).

- [ ] **Step 8.2: Run full verify + sonar (if MCP is up against `master`)**

Run: `mvn -q verify`
Expected: BUILD SUCCESS, all tests pass, coverage report generated.

SonarLint MCP is blind to worktrees per project memory — rely on the CI scan for the branch. Locally, check `mvn` output for warnings.

- [ ] **Step 8.3: Final commit (if any cleanup was needed)**

```bash
git status
# If clean, skip. Otherwise:
git add -A
SKIP=commitlint git commit -m "chore: Final cleanup of HttpServer leaks"
```

- [ ] **Step 8.4: Push and hand off to the user for PR creation**

```bash
git push -u origin feat/extra-route-interface
```

Per the project's `reference_gh_token.md` memory, the `gh` CLI cannot create PRs in this repo — the user opens the PR manually after the push.

---

## Spec coverage check

- §1 `Request.method()` → Task 1 ✓
- §2 `extraRoute(String, RequestHandler)` → Task 6 ✓
- §3 `ExtraRouteAdapter` → Task 6 ✓
- §4 `ExceptionHandler` returns `Response` → Task 4 ✓
- §4a `BadRequestException` → Task 2 ✓
- §4b `ProblemDetail` record → Task 3 ✓; renderer deletion → Task 7 ✓
- §5 `Handlers.*` factories return `RequestHandler` with inline 405 → Task 5 ✓
- §6 zero `com.sun.net.httpserver` in non-internal main → Task 8 ✓
- Non-goal: `ScopedValue` access from `ExceptionHandler` is documented in the new javadoc on `ExceptionHandler` (Step 4.1) ✓
