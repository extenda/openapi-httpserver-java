# Configurable bind address — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional `Builder.bindAddress(InetAddress)` to `OpenApiServer` so callers can restrict the server to a specific local interface (e.g., loopback) instead of always binding to the wildcard address.

**Architecture:** Additive change. `Builder` gains an `InetAddress bindAddress` field (default `null`). The package-private constructor receives it as a new parameter and picks between `new InetSocketAddress(port)` (wildcard) and `new InetSocketAddress(bindAddress, port)`. Default path is byte-identical to current behavior.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Mockito, JDK `com.sun.net.httpserver.HttpServer`.

---

## File Structure

- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`
  - Add `bindAddress` field on `Builder`, builder method, constructor parameter, `InetSocketAddress` construction switch, startup log host:port format.
- Modify: `src/test/java/com/retailsvc/http/OpenApiServerTest.java`
  - Add tests covering loopback binding, default wildcard binding, explicit-null behavior.
- Modify: `README.md`
  - Add a short loopback-binding snippet to the Getting Started area.

No new files.

---

### Task 1: Failing test for loopback binding

**Files:**
- Test: `src/test/java/com/retailsvc/http/OpenApiServerTest.java`

- [ ] **Step 1: Add imports and the failing test**

Add the following imports near the existing imports in `OpenApiServerTest.java`:

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
```

Append this test method to `OpenApiServerTest`:

```java
@Test
void shouldBindOnlyToLoopbackWhenBindAddressIsLoopback() throws IOException {
  try (var server =
      OpenApiServer.builder()
          .spec(testSpec())
          .handlers(emptyMap())
          .port(0)
          .bindAddress(InetAddress.getLoopbackAddress())
          .build()) {
    int port = server.listenPort();
    HttpURLConnection conn =
        (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/missing").toURL().openConnection();
    try {
      assertThat(conn.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND);
    } finally {
      conn.disconnect();
    }
  }
}
```

The handler map is empty and the path is unmapped — a 404 from the catch-all `/` context is sufficient to prove the server is listening on loopback.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=OpenApiServerTest#shouldBindOnlyToLoopbackWhenBindAddressIsLoopback`

Expected: FAIL (compilation error: `cannot find symbol: method bindAddress(InetAddress)`).

---

### Task 2: Add `bindAddress` to the builder and thread it through the constructor

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

- [ ] **Step 1: Add the `InetAddress` import**

Insert next to the existing `java.net.InetSocketAddress` import:

```java
import java.net.InetAddress;
```

- [ ] **Step 2: Add the constructor parameter and bind logic**

Change the constructor signature so `bindAddress` is threaded in as a new parameter (place it between `port` and `shutdownTimeoutSeconds`):

```java
OpenApiServer(
    Spec spec,
    Map<String, TypeMapper> bodyMappers,
    HandlerConfig handlerConfig,
    int port,
    InetAddress bindAddress,
    int shutdownTimeoutSeconds)
    throws IOException {
```

Replace the existing wildcard bind:

```java
this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
```

with:

```java
InetSocketAddress socketAddress =
    (bindAddress == null) ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port);
this.httpServer = HttpServer.create(socketAddress, 0);
```

Update the startup log line so the bound host is visible:

```java
LOG.info(
    "Server started ({}:{}) in {}ms",
    httpServer.getAddress().getHostString(),
    httpServer.getAddress().getPort(),
    System.currentTimeMillis() - t0);
```

- [ ] **Step 3: Add the builder field and method**

Inside `Builder`, add a field next to the existing `port` field:

```java
private InetAddress bindAddress;
```

Add the builder method (place it directly under `port(int)`):

```java
/**
 * Restricts the server to a specific local interface. {@code null} (the default) binds to the
 * wildcard address (all interfaces). Use {@link InetAddress#getLoopbackAddress()} to listen on
 * loopback only.
 */
public Builder bindAddress(InetAddress bindAddress) {
  this.bindAddress = bindAddress;
  return this;
}
```

Update the `build()` call to the constructor to pass `bindAddress`:

```java
return new OpenApiServer(spec, resolved, handlerConfig, port, bindAddress, shutdownTimeoutSeconds);
```

- [ ] **Step 4: Run the loopback test — expect PASS**

Run: `mvn test -Dtest=OpenApiServerTest#shouldBindOnlyToLoopbackWhenBindAddressIsLoopback`

Expected: PASS.

- [ ] **Step 5: Run the full unit-test suite to confirm no regressions**

Run: `mvn test`

Expected: all tests pass.

---

### Task 3: Failing test for default wildcard binding

**Files:**
- Test: `src/test/java/com/retailsvc/http/OpenApiServerTest.java`

- [ ] **Step 1: Add the failing test**

Append:

```java
@Test
void shouldBindToWildcardWhenBindAddressIsUnset() throws IOException {
  try (var server =
      OpenApiServer.builder().spec(testSpec()).handlers(emptyMap()).port(0).build()) {
    assertThat(server.bindAddress().isAnyLocalAddress()).isTrue();
  }
}

@Test
void shouldBindToWildcardWhenBindAddressIsExplicitlyNull() throws IOException {
  try (var server =
      OpenApiServer.builder()
          .spec(testSpec())
          .handlers(emptyMap())
          .port(0)
          .bindAddress(null)
          .build()) {
    assertThat(server.bindAddress().isAnyLocalAddress()).isTrue();
  }
}
```

These reference a yet-to-exist `bindAddress()` accessor on `OpenApiServer`.

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn test -Dtest=OpenApiServerTest#shouldBindToWildcardWhenBindAddressIsUnset+shouldBindToWildcardWhenBindAddressIsExplicitlyNull`

Expected: FAIL (compilation error: `cannot find symbol: method bindAddress()`).

---

### Task 4: Expose the bound address on `OpenApiServer`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`

- [ ] **Step 1: Add the accessor**

Add directly below the existing `listenPort()` method:

```java
/**
 * Returns the actual address the server is bound to, including any wildcard resolution by the
 * underlying {@link HttpServer}. Useful for verifying loopback restriction.
 */
public InetAddress bindAddress() {
  return httpServer.getAddress().getAddress();
}
```

- [ ] **Step 2: Run the wildcard tests to verify they pass**

Run: `mvn test -Dtest=OpenApiServerTest#shouldBindToWildcardWhenBindAddressIsUnset+shouldBindToWildcardWhenBindAddressIsExplicitlyNull`

Expected: PASS.

- [ ] **Step 3: Run the full unit-test suite**

Run: `mvn test`

Expected: all tests pass.

---

### Task 5: README snippet

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a loopback example**

In the section that documents builder configuration (under "Getting Started" / "Basic Usage", near the `port` mention if any), add:

````markdown
#### Restricting to the loopback interface

By default the server binds to the wildcard address (all local interfaces). To restrict it to loopback — useful for local development or sidecar processes — supply a bind address:

```java
import java.net.InetAddress;

OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .port(8080)
    .bindAddress(InetAddress.getLoopbackAddress())
    .build();
```
````

- [ ] **Step 2: Commit the full change**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/test/java/com/retailsvc/http/OpenApiServerTest.java \
        README.md
SKIP=commitlint git commit -m "feat: Support configurable bind address"
```

---

### Task 6: Final verification

- [ ] **Step 1: Run the full verification suite**

Run: `mvn verify`

Expected: build succeeds, all unit and integration tests pass.

- [ ] **Step 2: Analyze touched files with SonarLint MCP**

Per project memory: scan `OpenApiServer.java`, `OpenApiServerTest.java`, and any other modified files. Fix any new issues in the same branch before pushing.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin fix/support-loopback
```

Per project memory: `gh` cannot open PRs here — the user opens the PR manually after the branch is pushed.
