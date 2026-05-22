# Extras Wildcard Matching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `*` (single-segment) and `**` (any-depth) wildcards to extra routes, with strict path-traversal protection at the router layer.

**Architecture:** Replace the per-route `httpServer.createContext(path)` loop and the `/` `NotFoundHandler` with a single `ExtrasRouter` registered at `/`. The router validates the URI against a hard-coded traversal blocklist (raw + decoded), then dispatches to the matching exact or wildcard extra via the existing `ExtraRouteAdapter`. Matching is done by precompiled `java.util.regex.Pattern` per extra. The matched portion is NOT exposed to the handler.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver`, JUnit 5 + AssertJ + Mockito (existing test stack).

**Spec:** `docs/superpowers/specs/2026-05-22-extras-wildcard-design.md`

---

### Task 1: `PathPattern` — compile and match

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/PathPattern.java`
- Test: `src/test/java/com/retailsvc/http/internal/PathPatternTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PathPatternTest {

  @Test
  void exactPathHasNoWildcardAndMatchesItself() {
    PathPattern p = PathPattern.compile("/alive");
    assertThat(p.hasWildcard()).isFalse();
    assertThat(p.matches("/alive")).isTrue();
    assertThat(p.matches("/alive/")).isFalse();
    assertThat(p.matches("/alive232")).isFalse();
  }

  @Test
  void singleStarMatchesOneSegment() {
    PathPattern p = PathPattern.compile("/files/*");
    assertThat(p.hasWildcard()).isTrue();
    assertThat(p.matches("/files/a")).isTrue();
    assertThat(p.matches("/files/abc.txt")).isTrue();
    assertThat(p.matches("/files/")).isFalse();
    assertThat(p.matches("/files/a/b")).isFalse();
  }

  @Test
  void doubleStarMatchesAnyDepth() {
    PathPattern p = PathPattern.compile("/files/**");
    assertThat(p.matches("/files/")).isTrue();
    assertThat(p.matches("/files/a")).isTrue();
    assertThat(p.matches("/files/a/b/c")).isTrue();
    assertThat(p.matches("/files")).isFalse();
    assertThat(p.matches("/filesx/a")).isFalse();
  }

  @Test
  void midPathDoubleStarSurroundedByLiterals() {
    PathPattern p = PathPattern.compile("/schemas/**/openapi.yaml");
    assertThat(p.matches("/schemas/a/openapi.yaml")).isTrue();
    assertThat(p.matches("/schemas/a/b/openapi.yaml")).isTrue();
    assertThat(p.matches("/schemas/openapi.yaml")).isFalse();
    assertThat(p.matches("/schemas/a/openapi.yamlx")).isFalse();
  }

  @Test
  void mixedSegmentRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/files/prefix-*.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a whole segment");
  }

  @Test
  void emptySegmentRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/files//a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty segment");
  }

  @Test
  void adjacentDoubleStarsRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/a/**/**/b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("adjacent");
  }

  @Test
  void mustStartWithSlash() {
    assertThatThrownBy(() -> PathPattern.compile("files/*"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must start with '/'");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PathPatternTest`
Expected: compile error — `PathPattern` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.retailsvc.http.internal;

import java.util.regex.Pattern;

public final class PathPattern {

  private final String raw;
  private final Pattern regex;
  private final boolean wildcard;

  private PathPattern(String raw, Pattern regex, boolean wildcard) {
    this.raw = raw;
    this.regex = regex;
    this.wildcard = wildcard;
  }

  public static PathPattern compile(String raw) {
    if (raw == null || !raw.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/': " + raw);
    }
    String[] segments = raw.substring(1).split("/", -1);
    StringBuilder rx = new StringBuilder("^");
    boolean hasWildcard = false;
    String prev = null;
    for (int i = 0; i < segments.length; i++) {
      String seg = segments[i];
      if (seg.isEmpty() && !(i == segments.length - 1 && segments.length > 1 && raw.endsWith("/"))) {
        throw new IllegalArgumentException("empty segment in path: " + raw);
      }
      if (seg.contains("*") && !seg.equals("*") && !seg.equals("**")) {
        throw new IllegalArgumentException(
            "'*' and '**' must be a whole segment, not " + seg + " in " + raw);
      }
      if ("**".equals(seg) && "**".equals(prev)) {
        throw new IllegalArgumentException("adjacent '**' segments in " + raw);
      }
      rx.append("/");
      switch (seg) {
        case "*" -> {
          rx.append("[^/]+");
          hasWildcard = true;
        }
        case "**" -> {
          rx.setLength(rx.length() - 1);
          rx.append("(?:/.*)?");
          hasWildcard = true;
        }
        default -> rx.append(Pattern.quote(seg));
      }
      prev = seg;
    }
    rx.append("$");
    return new PathPattern(raw, Pattern.compile(rx.toString()), hasWildcard);
  }

  public boolean hasWildcard() {
    return wildcard;
  }

  public boolean matches(String path) {
    return regex.matcher(path).matches();
  }

  public String raw() {
    return raw;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PathPatternTest`
Expected: 8 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/PathPattern.java \
        src/test/java/com/retailsvc/http/internal/PathPatternTest.java
git commit -m "feat: Add PathPattern for extras wildcard matching"
```

---

### Task 2: `ExtrasPathValidator` — traversal protection

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ExtrasPathValidator.java`
- Test: `src/test/java/com/retailsvc/http/internal/ExtrasPathValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.BadRequestException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ExtrasPathValidatorTest {

  @Test
  void plainPathPasses() {
    URI uri = URI.create("/files/a/b.txt");
    assertThat(ExtrasPathValidator.validateAndDecode(uri)).isEqualTo("/files/a/b.txt");
  }

  @Test
  void dotDotSegmentRejected() {
    assertReject("/files/../etc/passwd");
  }

  @Test
  void singleDotSegmentRejected() {
    assertReject("/files/./x");
  }

  @Test
  void emptySegmentRejected() {
    assertReject("/files//x");
  }

  @Test
  void encodedDotRejected() {
    assertReject("/files/%2e%2e/etc/passwd");
    assertReject("/files/%2E/x");
  }

  @Test
  void doubleEncodedDotRejected() {
    assertReject("/files/%252e%252e/etc/passwd");
  }

  @Test
  void encodedSlashRejected() {
    assertReject("/files/%2fetc/passwd");
    assertReject("/files/%2Fetc/passwd");
  }

  @Test
  void backslashRejected() {
    assertReject("/files/x%5cy");
    assertReject("/files/x%5Cy");
  }

  @Test
  void literalBackslashRejected() {
    URI uri = URI.create("/files/x\\y");
    assertThatThrownBy(() -> ExtrasPathValidator.validateAndDecode(uri))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void nulByteRejected() {
    assertReject("/files/x%00.txt");
  }

  @Test
  void controlCharRejected() {
    assertReject("/files/x%0ay");
  }

  @Test
  void malformedEncodingRejected() {
    assertReject("/files/%zz");
  }

  private void assertReject(String raw) {
    URI uri = URI.create(raw);
    assertThatThrownBy(() -> ExtrasPathValidator.validateAndDecode(uri))
        .isInstanceOf(BadRequestException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ExtrasPathValidatorTest`
Expected: compile error — `ExtrasPathValidator` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ExtrasPathValidator {

  private static final Pattern ENCODED_BLOCKLIST =
      Pattern.compile("(?i)%(?:2e|2f|5c|00|0[1-9a-f]|1[0-9a-f]|7f)");

  private ExtrasPathValidator() {}

  public static String validateAndDecode(URI uri) {
    String raw = uri.getRawPath();
    if (raw == null) {
      throw new BadRequestException("missing path");
    }
    if (ENCODED_BLOCKLIST.matcher(raw).find()) {
      throw new BadRequestException("path contains disallowed percent-encoded sequence");
    }
    if (raw.indexOf('\\') >= 0) {
      throw new BadRequestException("path contains backslash");
    }
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new BadRequestException("path contains control character");
      }
    }

    String decoded;
    try {
      decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("malformed percent-encoding");
    }

    for (int i = 0; i < decoded.length(); i++) {
      char c = decoded.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new BadRequestException("decoded path contains control character");
      }
    }

    String[] segments = decoded.substring(decoded.startsWith("/") ? 1 : 0).split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      String s = segments[i];
      if (s.isEmpty() && i != segments.length - 1) {
        throw new BadRequestException("empty path segment");
      }
      if (".".equals(s) || "..".equals(s)) {
        throw new BadRequestException("path traversal segment");
      }
    }

    return decoded;
  }
}
```

Note: `URLDecoder.decode` treats `+` as space. This is acceptable here — we re-emit decoded only for matching against pre-decoded extra paths that themselves never contain `+`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ExtrasPathValidatorTest`
Expected: 12 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ExtrasPathValidator.java \
        src/test/java/com/retailsvc/http/internal/ExtrasPathValidatorTest.java
git commit -m "feat: Add ExtrasPathValidator for traversal protection"
```

---

### Task 3: `ExtrasRouter` — dispatch with exact and wildcard matching

**Files:**
- Create: `src/main/java/com/retailsvc/http/internal/ExtrasRouter.java`
- Test: `src/test/java/com/retailsvc/http/internal/ExtrasRouterTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.GsonTypeMapper;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtrasRouterTest {

  @Test
  void exactMatchDispatches() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/alive", req -> {
      hit.set("alive");
      return Response.empty();
    });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/alive");

    assertThat(hit.get()).isEqualTo("alive");
  }

  @Test
  void exactMatchRequiresExactPath() {
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/alive", req -> Response.empty());
    ExtrasRouter router = newRouter(extras);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(router, "/alive232"))
        .isInstanceOf(NotFoundException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(router, "/alive/34"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void singleStarMatchesOneSegment() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/static/*", req -> {
      hit.set("static");
      return Response.empty();
    });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/static/style.css");
    assertThat(hit.get()).isEqualTo("static");
  }

  @Test
  void doubleStarMatchesAnyDepth() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/files/**", req -> {
      hit.set("files");
      return Response.empty();
    });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/files/a/b/c");
    assertThat(hit.get()).isEqualTo("files");
  }

  @Test
  void exactWinsOverWildcard() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/files/**", req -> {
      hit.set("wild");
      return Response.empty();
    });
    extras.put("/files/special", req -> {
      hit.set("exact");
      return Response.empty();
    });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/files/special");
    assertThat(hit.get()).isEqualTo("exact");
  }

  @Test
  void noMatchThrowsNotFound() {
    ExtrasRouter router = newRouter(Map.of());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(router, "/nope"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void traversalRejected() {
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/files/**", req -> Response.empty());
    ExtrasRouter router = newRouter(extras);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> invoke(router, "/files/../etc/passwd"))
        .isInstanceOf(BadRequestException.class);
  }

  private static ExtrasRouter newRouter(Map<String, RequestHandler> extras) {
    Map<String, TypeMapper> mappers = Map.of("application/json", new GsonTypeMapper());
    return new ExtrasRouter(extras, new ResponseRenderer(mappers));
  }

  private static void invoke(ExtrasRouter router, String path) throws Exception {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn("GET");
    when(ex.getRequestURI()).thenReturn(URI.create(path));
    when(ex.getRequestHeaders()).thenReturn(new Headers());
    when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    router.handle(ex);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ExtrasRouterTest`
Expected: compile error — `ExtrasRouter` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.retailsvc.http.internal;

import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.HttpMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtrasRouter implements HttpHandler {

  private record Entry(PathPattern pattern, RequestHandler handler) {}

  private final Map<String, RequestHandler> exact;
  private final List<Entry> wildcards;
  private final ResponseRenderer renderer;

  public ExtrasRouter(Map<String, RequestHandler> extras, ResponseRenderer renderer) {
    this.renderer = renderer;
    Map<String, RequestHandler> exactBuilder = new LinkedHashMap<>();
    List<Entry> wildcardBuilder = new ArrayList<>();
    for (Map.Entry<String, RequestHandler> e : extras.entrySet()) {
      PathPattern p = PathPattern.compile(e.getKey());
      if (p.hasWildcard()) {
        wildcardBuilder.add(new Entry(p, e.getValue()));
      } else {
        exactBuilder.put(p.raw(), e.getValue());
      }
    }
    this.exact = Map.copyOf(exactBuilder);
    this.wildcards = List.copyOf(wildcardBuilder);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String decoded = ExtrasPathValidator.validateAndDecode(exchange.getRequestURI());

    RequestHandler hit = exact.get(decoded);
    if (hit == null) {
      for (Entry e : wildcards) {
        if (e.pattern().matches(decoded)) {
          hit = e.handler();
          break;
        }
      }
    }
    if (hit == null) {
      throw new NotFoundException(exchange.getRequestMethod() + " " + decoded);
    }

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
    Response response = hit.handle(request);
    renderer.render(exchange, response);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ExtrasRouterTest`
Expected: 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/ExtrasRouter.java \
        src/test/java/com/retailsvc/http/internal/ExtrasRouterTest.java
git commit -m "feat: Add ExtrasRouter with exact and wildcard matching"
```

---

### Task 4: Wire `ExtrasRouter` into `OpenApiServer`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java:137-145`
- Delete: `src/main/java/com/retailsvc/http/internal/ExtraRouteAdapter.java` (replaced by `ExtrasRouter` — its `Request`-building logic is inlined into the router)
- Delete: `src/test/java/com/retailsvc/http/internal/ExtraRouteAdapterTest.java`
- Modify (if still referenced): `src/main/java/com/retailsvc/http/internal/NotFoundHandler.java` — keep file but the registration at `/` goes away.

- [ ] **Step 1: Replace the extras registration block**

Open `OpenApiServer.java`. Locate lines 137-145 (currently):

```java
    for (Map.Entry<String, RequestHandler> e : handlerConfig.extras().entrySet()) {
      HttpContext extraCtx = httpServer.createContext(e.getKey());
      extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
      extraCtx.setHandler(new ExtraRouteAdapter(e.getValue(), renderer));
    }

    if (!"/".equals(basePath)) {
      httpServer.createContext("/", new NotFoundHandler());
    }
```

Replace with:

```java
    ExtrasRouter extrasRouter = new ExtrasRouter(handlerConfig.extras(), renderer);
    if (!"/".equals(basePath)) {
      HttpContext extrasCtx = httpServer.createContext("/", extrasRouter);
      extrasCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
    } else {
      // basePath is "/"; spec context already owns "/". Extras may only be
      // registered alongside a non-"/" basePath, so reject at build time.
      if (!handlerConfig.extras().isEmpty()) {
        throw new IllegalStateException(
            "extras cannot be registered when basePath is '/'");
      }
    }
```

Update imports in `OpenApiServer.java`:

- Remove: `import com.retailsvc.http.internal.ExtraRouteAdapter;`
- Remove: `import com.retailsvc.http.internal.NotFoundHandler;`
- Add: `import com.retailsvc.http.internal.ExtrasRouter;`

- [ ] **Step 2: Delete obsolete files**

```bash
git rm \
  src/main/java/com/retailsvc/http/internal/ExtraRouteAdapter.java \
  src/test/java/com/retailsvc/http/internal/ExtraRouteAdapterTest.java \
  src/main/java/com/retailsvc/http/internal/NotFoundHandler.java
```

- [ ] **Step 3: Run unit tests**

Run: `mvn test`
Expected: all unit tests pass.

- [ ] **Step 4: Run integration tests**

Run: `mvn verify`
Expected: all IT tests pass, including the existing `ExtraHandlersIT`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Replace per-route extras wiring with ExtrasRouter"
```

---

### Task 5: Integration test `ExtrasWildcardIT`

**Files:**
- Create: `src/test/java/com/retailsvc/http/ExtrasWildcardIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtrasWildcardIT extends ServerBaseTest {

  @Test
  void singleStarMatchesOneSegment() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/static/*", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/static/x.css").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/static/a/b").statusCode()).isEqualTo(HTTP_NOT_FOUND);
      assertThat(get(client, s, "/static/").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void doubleStarMatchesAnyDepth() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/files/**", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/files/a").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/files/a/b/c").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/filesx/a").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void midPathDoubleStar() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/schemas/**/openapi.yaml", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/schemas/a/openapi.yaml").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/schemas/a/b/openapi.yaml").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/schemas/openapi.yaml").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void exactExtraStillWorks() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/alive").statusCode()).isEqualTo(204);
      assertThat(get(client, s, "/alive232").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void traversalReturns400() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/files/**", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/files/../etc/passwd").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%2e%2e/etc/passwd").statusCode())
          .isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%252e%252e/etc/passwd").statusCode())
          .isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%2fetc").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/x%5cy").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/x%00").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/x%0ay").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%zz").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files//x").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/.").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
    }
  }

  @Test
  void basePathSpecRouteWinsOverExtras() throws Exception {
    RequestHandler greedy = req -> Response.of(HTTP_OK, "should not see this");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/**", greedy)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/api/v1/data").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/totally-not-a-spec-route").statusCode()).isEqualTo(HTTP_OK);
    }
  }

  private HttpResponse<String> get(HttpClient client, OpenApiServer s, String path)
      throws Exception {
    var req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + s.listenPort() + path))
            .GET()
            .build();
    return client.send(req, BodyHandlers.ofString());
  }
}
```

- [ ] **Step 2: Run integration tests**

Run: `mvn verify -Dit.test=ExtrasWildcardIT`
Expected: all tests pass.

- [ ] **Step 3: Run full build**

Run: `mvn verify`
Expected: all tests pass; no surefire/failsafe regressions.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/retailsvc/http/ExtrasWildcardIT.java
git commit -m "test: Add integration tests for extras wildcard matching"
```

---

### Task 6: README documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Locate the extras section**

Run: `grep -n "extraRoute\|Extras" README.md | head`
Identify the section that documents `extraRoute`.

- [ ] **Step 2: Add wildcard subsection**

Append to the extras section:

```markdown
### Wildcards in extra routes

Extra routes accept two wildcard tokens (these are *not* part of OpenAPI;
they apply only to extras, which are outside the spec):

- `*` — matches exactly one path segment (no `/`).
- `**` — matches zero or more characters, may cross `/` boundaries.

Both must appear as whole segments (`/files/*`, `/files/**`,
`/schemas/**/openapi.yaml`). Mixed-segment patterns like `prefix-*.json`
are rejected at boot.

The matched portion is not exposed to the handler. If you map a wildcard
extra to a filesystem location, canonicalise via `Path.toRealPath()` and
assert `resolved.startsWith(baseReal)` to prevent escape — the router
blocks `.`, `..`, encoded `%2e`/`%2f`/`%5c`/`%00`, control characters and
malformed encoding with a 400, but cannot police what the handler does
with the matched path.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: Document extras wildcard syntax in README"
```

---

## Done

Branch `feat/extras-wildcard` is ready to push. Open a PR; the user opens it manually (gh CLI cannot create PRs in this repo).
