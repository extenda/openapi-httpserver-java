# HTTPS support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Builder.https(Path certChain, Path privateKey)` so consumers can serve HTTPS by pointing the library at PEM files written by certbot / Let's Encrypt — no PKCS12 keystore conversion, no BouncyCastle.

**Architecture:** A package-private `PemSslContext.load(Path, Path)` turns the two PEM files into a `javax.net.ssl.SSLContext` using only JDK APIs (`CertificateFactory`, `PKCS8EncodedKeySpec`, `KeyFactory`, in-memory `PKCS12 KeyStore`, `KeyManagerFactory`, `SSLContext`). `OpenApiServer.build()` switches from `HttpServer.create(...)` to `HttpsServer.create(...) + setHttpsConfigurator(new HttpsConfigurator(sslContext))` when the HTTPS fields are populated. Default port flips to 8443 when HTTPS is enabled.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver.HttpsServer`, `javax.net.ssl`, `java.security`. JUnit 5 + AssertJ + Java 11 `HttpClient` for the integration test. `openssl` (host tool, one-time) to generate test fixtures.

**Spec:** `docs/superpowers/specs/2026-05-21-https-support-design.md`

---

## File structure

**Production code**

- Create: `src/main/java/com/retailsvc/http/internal/PemSslContext.java` — package-private utility. Single static method `load(Path certChainPem, Path privateKeyPem) -> SSLContext`. Owns all PEM parsing and `SSLContext` assembly.
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` — add `httpsCertChain` / `httpsPrivateKey` fields on `Builder`, change `port` field from `int` to `Integer` so we can detect "user set" vs "default", add `Builder.https(Path, Path)` public method, branch on HTTPS in `build()` to construct `HttpsServer` instead of `HttpServer`. Constructor gets two new parameters (cert chain, key).

**Tests**

- Create: `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java` — unit tests for happy paths (RSA, EC) and error paths (missing file, garbage PEM, key/cert mismatch).
- Create: `src/test/java/com/retailsvc/http/OpenApiServerHttpsIT.java` — boots the server with `.https(...)` on port `0`, hits it with a `HttpClient` configured to trust the test cert, asserts 200 + body. Repeated for RSA and EC fixtures.

**Test fixtures**

- Create: `src/test/resources/tls/rsa-cert.pem`, `src/test/resources/tls/rsa-key.pem` — self-signed RSA cert + matching PKCS#8 key for `CN=localhost`.
- Create: `src/test/resources/tls/ec-cert.pem`, `src/test/resources/tls/ec-key.pem` — self-signed P-256 EC cert + matching PKCS#8 key for `CN=localhost`.
- Create: `src/test/resources/tls/mismatched-key.pem` — a second RSA private key that does NOT match `rsa-cert.pem`.
- Create: `src/test/resources/tls/garbage.pem` — PEM headers wrapping random bytes.

**Docs**

- Modify: `README.md` — new `### HTTPS` subsection under `## Server configuration`, placed immediately before `### Graceful shutdown`. Table of contents updated.

---

## Task 1: Generate and commit test fixtures

**Files:**

- Create: `src/test/resources/tls/rsa-cert.pem`
- Create: `src/test/resources/tls/rsa-key.pem`
- Create: `src/test/resources/tls/ec-cert.pem`
- Create: `src/test/resources/tls/ec-key.pem`
- Create: `src/test/resources/tls/mismatched-key.pem`
- Create: `src/test/resources/tls/garbage.pem`

These are deterministic test fixtures, not secrets. They're committed once and reused.

- [x] **Step 1: Ensure the fixture directory exists**

Run:

```bash
mkdir -p src/test/resources/tls
```

- [x] **Step 2: Generate the RSA self-signed cert + PKCS#8 key**

Run from the repo root:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout src/test/resources/tls/rsa-key.pem \
  -out    src/test/resources/tls/rsa-cert.pem \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

`-nodes` makes the key unencrypted. `openssl req` writes PKCS#8 (`-----BEGIN PRIVATE KEY-----`) by default on modern OpenSSL (1.1.1+). `subjectAltName` is required for Java's `HttpClient` to accept the cert without warning.

- [x] **Step 3: Verify the RSA key is PKCS#8 unencrypted**

Run:

```bash
head -1 src/test/resources/tls/rsa-key.pem
```

Expected: `-----BEGIN PRIVATE KEY-----` (not `-----BEGIN RSA PRIVATE KEY-----` or `-----BEGIN ENCRYPTED PRIVATE KEY-----`). If the header is wrong, the OpenSSL on this machine writes legacy PKCS#1; convert with:

```bash
openssl pkcs8 -topk8 -nocrypt -in src/test/resources/tls/rsa-key.pem \
  -out src/test/resources/tls/rsa-key.pem.tmp \
  && mv src/test/resources/tls/rsa-key.pem.tmp src/test/resources/tls/rsa-key.pem
```

- [x] **Step 4: Generate the EC (P-256) self-signed cert + PKCS#8 key**

Run:

```bash
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
  -keyout src/test/resources/tls/ec-key.pem \
  -out    src/test/resources/tls/ec-cert.pem \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

- [x] **Step 5: Verify the EC key is PKCS#8 unencrypted**

Run:

```bash
head -1 src/test/resources/tls/ec-key.pem
```

Expected: `-----BEGIN PRIVATE KEY-----`. If it says `-----BEGIN EC PRIVATE KEY-----`, convert:

```bash
openssl pkcs8 -topk8 -nocrypt -in src/test/resources/tls/ec-key.pem \
  -out src/test/resources/tls/ec-key.pem.tmp \
  && mv src/test/resources/tls/ec-key.pem.tmp src/test/resources/tls/ec-key.pem
```

- [x] **Step 6: Generate a second RSA key (does NOT match `rsa-cert.pem`)**

Run:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out src/test/resources/tls/mismatched-key.pem
```

This produces an unencrypted PKCS#8 key by default.

- [x] **Step 7: Create a deliberately-broken PEM file**

Write `src/test/resources/tls/garbage.pem` with this exact content:

```
-----BEGIN CERTIFICATE-----
this is not actually base64 encoded certificate data at all
-----END CERTIFICATE-----
```

- [x] **Step 8: Sanity-check both happy-path fixtures**

Run:

```bash
openssl x509 -in src/test/resources/tls/rsa-cert.pem -noout -subject -dates
openssl x509 -in src/test/resources/tls/ec-cert.pem  -noout -subject -dates
openssl pkey -in src/test/resources/tls/rsa-key.pem -noout -text 2>&1 | head -3
openssl pkey -in src/test/resources/tls/ec-key.pem  -noout -text 2>&1 | head -3
```

Expected: subject `CN = localhost`, validity ~10 years, key text confirms RSA 2048 and EC P-256 respectively.

- [x] **Step 9: Commit the fixtures**

```bash
git add src/test/resources/tls/
SKIP=commitlint git commit -m "test: Add TLS PEM fixtures for HTTPS support"
```

---

## Task 2: PemSslContext — RSA happy path (TDD)

**Files:**

- Create: `src/main/java/com/retailsvc/http/internal/PemSslContext.java`
- Create: `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java`

- [x] **Step 1: Write the failing RSA happy-path test**

Write `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java`:

```java
package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemSslContextTest {

  private static final Path RSA_CERT = Path.of("src/test/resources/tls/rsa-cert.pem");
  private static final Path RSA_KEY = Path.of("src/test/resources/tls/rsa-key.pem");

  @Test
  void loadsRsaPemPair() throws Exception {
    SSLContext context = PemSslContext.load(RSA_CERT, RSA_KEY);

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
    assertThat(context.getServerSocketFactory()).isNotNull();
  }
}
```

- [x] **Step 2: Run the test, confirm it fails**

```bash
mvn test -Dtest=PemSslContextTest
```

Expected: compilation failure — `PemSslContext` does not exist.

- [x] **Step 3: Create the minimal `PemSslContext`**

Write `src/main/java/com/retailsvc/http/internal/PemSslContext.java`:

```java
package com.retailsvc.http.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Loads a {@link SSLContext} from a PEM certificate chain and PEM PKCS#8 private key. */
public final class PemSslContext {

  private PemSslContext() {}

  public static SSLContext load(Path certChainPem, Path privateKeyPem) {
    Certificate[] chain = readCertificateChain(certChainPem);
    PrivateKey key = readPrivateKey(privateKeyPem);
    return buildSslContext(chain, key);
  }

  private static Certificate[] readCertificateChain(Path path) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read TLS certificate chain: " + path, e);
    }
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> certs =
          factory.generateCertificates(new java.io.ByteArrayInputStream(bytes));
      return certs.toArray(new Certificate[0]);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse TLS certificate chain from " + path, e);
    }
  }

  private static PrivateKey readPrivateKey(Path path) {
    String pem;
    try {
      pem = Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read TLS private key: " + path, e);
    }
    byte[] der;
    try {
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      der = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + path, e);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (InvalidKeySpecException rsaFail) {
      throw new IllegalStateException("Failed to parse TLS private key from " + path, rsaFail);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + path, e);
    }
  }

  private static SSLContext buildSslContext(Certificate[] chain, PrivateKey key) {
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(null, null);
      ks.setKeyEntry("server", key, new char[0], chain);
      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(ks, new char[0]);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      return ctx;
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("TLS certificate and private key do not match", e);
    }
  }
}
```

- [x] **Step 4: Run the test, confirm it passes**

```bash
mvn test -Dtest=PemSslContextTest
```

Expected: 1 test passes.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/PemSslContext.java \
        src/test/java/com/retailsvc/http/internal/PemSslContextTest.java
SKIP=commitlint git commit -m "feat: Add PemSslContext for loading PEM cert + RSA key"
```

---

## Task 3: PemSslContext — EC support (TDD)

**Files:**

- Modify: `src/main/java/com/retailsvc/http/internal/PemSslContext.java`
- Modify: `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java`

- [x] **Step 1: Add the failing EC test**

Append to `PemSslContextTest`:

```java
  private static final Path EC_CERT = Path.of("src/test/resources/tls/ec-cert.pem");
  private static final Path EC_KEY = Path.of("src/test/resources/tls/ec-key.pem");

  @Test
  void loadsEcPemPair() throws Exception {
    SSLContext context = PemSslContext.load(EC_CERT, EC_KEY);

    assertThat(context).isNotNull();
    assertThat(context.getServerSocketFactory()).isNotNull();
  }
```

- [x] **Step 2: Run, confirm it fails**

```bash
mvn test -Dtest=PemSslContextTest#loadsEcPemPair
```

Expected: FAIL — `InvalidKeySpecException` wrapped in `IllegalStateException` from RSA `KeyFactory` rejecting EC bytes.

- [x] **Step 3: Extend `readPrivateKey` with EC fallback**

In `PemSslContext.java`, replace the entire `readPrivateKey` method with:

```java
  private static PrivateKey readPrivateKey(Path path) {
    String pem;
    try {
      pem = Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read TLS private key: " + path, e);
    }
    byte[] der;
    try {
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      der = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + path, e);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (InvalidKeySpecException rsaFail) {
      try {
        return KeyFactory.getInstance("EC").generatePrivate(spec);
      } catch (InvalidKeySpecException ecFail) {
        throw new IllegalStateException(
            "Unsupported TLS private key algorithm in " + path, ecFail);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Failed to parse TLS private key from " + path, e);
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + path, e);
    }
  }
```

- [x] **Step 4: Run both happy-path tests**

```bash
mvn test -Dtest=PemSslContextTest
```

Expected: 2 tests pass.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/PemSslContext.java \
        src/test/java/com/retailsvc/http/internal/PemSslContextTest.java
SKIP=commitlint git commit -m "feat: Support EC private keys in PemSslContext"
```

---

## Task 4: PemSslContext — error paths (TDD)

**Files:**

- Modify: `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java`

The error messages are already produced by Task 2/3's implementation. This task just asserts them.

- [x] **Step 1: Add the failing missing-file test**

Append to `PemSslContextTest`:

```java
  private static final Path MISMATCHED_KEY = Path.of("src/test/resources/tls/mismatched-key.pem");
  private static final Path GARBAGE = Path.of("src/test/resources/tls/garbage.pem");
  private static final Path MISSING = Path.of("src/test/resources/tls/does-not-exist.pem");

  @Test
  void rejectsMissingCertFile() {
    assertThatThrownBy(() -> PemSslContext.load(MISSING, RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read TLS certificate chain")
        .hasMessageContaining("does-not-exist.pem");
  }

  @Test
  void rejectsMissingKeyFile() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, MISSING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read TLS private key")
        .hasMessageContaining("does-not-exist.pem");
  }

  @Test
  void rejectsGarbageCertPem() {
    assertThatThrownBy(() -> PemSslContext.load(GARBAGE, RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse TLS certificate chain");
  }

  @Test
  void rejectsGarbageKeyPem() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, GARBAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse TLS private key");
  }

  @Test
  void rejectsMismatchedCertAndKey() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, MISMATCHED_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match");
  }
```

Add the import at the top of the file:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [x] **Step 2: Run, confirm all five pass**

```bash
mvn test -Dtest=PemSslContextTest
```

Expected: 7 tests pass (2 happy + 5 negative).

If the mismatched-key test fails because the key parsed cleanly but the cert→key binding wasn't detected, that's a real bug in `buildSslContext` — `KeyManagerFactory.init` is what surfaces the mismatch, and it does. Verify by reading the actual message: it should contain "do not match" via the chained cause. If `KeyManagerFactory` accepts the pair, the test will fail; do NOT relax the assertion — debug instead.

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/retailsvc/http/internal/PemSslContextTest.java
SKIP=commitlint git commit -m "test: Cover PemSslContext error paths"
```

---

## Task 5: Add `Builder.https(...)` and wire `HttpsServer` (TDD)

**Files:**

- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java`
- Create: `src/test/java/com/retailsvc/http/OpenApiServerHttpsIT.java`

This task adds the public builder method, switches the constructor to create `HttpsServer` when HTTPS is configured, and proves it end-to-end with a real HTTPS round-trip.

- [x] **Step 1: Add an OpenAPI fixture for the HTTPS IT**

Reuse the existing `src/test/resources/openapi.json` (or whichever spec the existing `OpenApiServerIT` uses). Confirm the spec path used by the existing IT:

```bash
grep -l "Spec.from\|fromPath\|fromJson\|fromYaml" src/test/java/com/retailsvc/http/OpenApiServerIT.java
```

Read the resolved test spec path and reuse it in the new IT.

- [x] **Step 2: Write the failing integration test**

Write `src/test/java/com/retailsvc/http/OpenApiServerHttpsIT.java`:

```java
package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenApiServerHttpsIT {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "rsa, src/test/resources/tls/rsa-cert.pem, src/test/resources/tls/rsa-key.pem",
      "ec,  src/test/resources/tls/ec-cert.pem,  src/test/resources/tls/ec-key.pem"
  })
  void servesHttpsTraffic(String algo, String certPath, String keyPath) throws Exception {
    Path cert = Path.of(certPath);
    Path key = Path.of(keyPath);

    Spec spec;
    try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
      spec = Spec.fromJson(in);
    }

    RequestHandler handler = req -> Response.ok(Map.of("hello", "world"));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getThings", handler))
            .port(0)
            .https(cert, key)
            .build()) {

      HttpClient client = HttpClient.newBuilder()
          .version(HTTP_1_1)
          .sslContext(trustStoreFor(cert))
          .build();

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("https://localhost:" + server.listenPort() + "/things"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"hello\":\"world\"");
    }
  }

  private static SSLContext trustStoreFor(Path certPath) throws Exception {
    byte[] bytes = Files.readAllBytes(certPath);
    Certificate cert =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(bytes));
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("server", cert);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(new KeyManager[0], tmf.getTrustManagers(), null);
    return ctx;
  }
}
```

NOTE: This IT assumes the test spec at `/openapi.json` declares an operationId `getThings` on `GET /things` with no required parameters, no required body, and no security. If the existing test spec uses different operationIds, adjust the `handler` map and the URI path to match an operation that returns a `Response.ok(...)` cleanly. Confirm by skimming `src/test/resources/openapi.json` before running.

- [x] **Step 3: Run, confirm it fails to compile**

```bash
mvn verify -Dit.test=OpenApiServerHttpsIT -DfailIfNoTests=false
```

Expected: compilation failure — `OpenApiServer.Builder.https(Path, Path)` does not exist.

- [x] **Step 4: Add HTTPS fields and the public method on `Builder`**

In `src/main/java/com/retailsvc/http/OpenApiServer.java`:

1. Add the import:

```java
import java.nio.file.Path;
import com.retailsvc.http.internal.PemSslContext;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.SSLContext;
```

2. Change the existing `port` field:

```java
    private int port = DEFAULT_PORT;
```

to:

```java
    private Integer port;
```

3. Add two new fields next to `port`:

```java
    private Path httpsCertChain;
    private Path httpsPrivateKey;
```

4. Add the new constant next to `DEFAULT_PORT`:

```java
  private static final int DEFAULT_HTTPS_PORT = 8443;
```

5. Update `Builder.port(int)`'s Javadoc to mention the new default behaviour, and assign as before:

```java
    /**
     * Sets the TCP port to listen on. Defaults to {@value #DEFAULT_PORT} for HTTP and {@value
     * #DEFAULT_HTTPS_PORT} when {@link #https(Path, Path)} is set. Use {@code 0} to bind on an
     * ephemeral port (read it back via {@link OpenApiServer#listenPort()}).
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }
```

6. Add the new builder method directly after `bindAddress(...)`:

```java
    /**
     * Enables HTTPS using the given PEM-encoded certificate chain and PKCS#8 private key. Both
     * files must exist when {@link #build()} runs; failures surface as {@link
     * IllegalStateException} with the offending path. The certificate file is a PEM concatenation
     * of the server certificate followed by any intermediates (matches certbot's {@code
     * fullchain.pem}). The private key is an unencrypted PKCS#8 PEM (matches certbot's {@code
     * privkey.pem}); RSA and EC keys are both accepted.
     *
     * <p>When set, the default port changes from {@value #DEFAULT_PORT} to {@value
     * #DEFAULT_HTTPS_PORT}; {@link #port(int)} still overrides.
     */
    public Builder https(Path certificateChainPem, Path privateKeyPem) {
      this.httpsCertChain = requireNonNull(certificateChainPem, "certificateChainPem must not be null");
      this.httpsPrivateKey = requireNonNull(privateKeyPem, "privateKeyPem must not be null");
      return this;
    }
```

7. In `Builder.build()`, replace the final `return new OpenApiServer(...)` block with port resolution + SSLContext load + the new constructor call:

Replace:

```java
      return new OpenApiServer(
          spec, resolved, handlerConfig, port, bindAddress, shutdownTimeoutSeconds);
```

with:

```java
      int resolvedPort =
          port != null ? port : (httpsCertChain != null ? DEFAULT_HTTPS_PORT : DEFAULT_PORT);
      SSLContext sslContext =
          httpsCertChain != null ? PemSslContext.load(httpsCertChain, httpsPrivateKey) : null;
      return new OpenApiServer(
          spec, resolved, handlerConfig, resolvedPort, bindAddress, shutdownTimeoutSeconds, sslContext);
```

- [x] **Step 5: Update the constructor to optionally build an `HttpsServer`**

In `OpenApiServer.java`, change the constructor signature from:

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

to:

```java
  OpenApiServer(
      Spec spec,
      Map<String, TypeMapper> bodyMappers,
      HandlerConfig handlerConfig,
      int port,
      InetAddress bindAddress,
      int shutdownTimeoutSeconds,
      SSLContext sslContext)
      throws IOException {
```

Replace the block:

```java
    this.httpServer = HttpServer.create(socketAddress, 0);
```

with:

```java
    if (sslContext != null) {
      HttpsServer https = HttpsServer.create(socketAddress, 0);
      https.setHttpsConfigurator(new HttpsConfigurator(sslContext));
      this.httpServer = https;
    } else {
      this.httpServer = HttpServer.create(socketAddress, 0);
    }
```

- [x] **Step 6: Run unit tests, confirm nothing regressed**

```bash
mvn test
```

Expected: every existing test still passes. The `Builder` change from `int port = DEFAULT_PORT` to `Integer port` plus default-resolution in `build()` is observationally identical to the old behaviour for HTTP callers.

- [x] **Step 7: Run the new HTTPS IT**

```bash
mvn verify -Dit.test=OpenApiServerHttpsIT -DfailIfNoTests=false
```

Expected: both `[rsa]` and `[ec]` parameterised cases pass.

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/retailsvc/http/OpenApiServer.java \
        src/test/java/com/retailsvc/http/OpenApiServerHttpsIT.java
SKIP=commitlint git commit -m "feat: Enable HTTPS via Builder.https(certChain, privateKey)"
```

---

## Task 6: README documentation

**Files:**

- Modify: `README.md`

- [x] **Step 1: Add the table-of-contents entry**

In the `## Table of contents` block, under `## Server configuration`, change:

```markdown
- [Server configuration](#server-configuration)
```

to add a nested HTTPS link (mirroring the existing nesting style if present, otherwise just add a sibling link directly below):

```markdown
- [Server configuration](#server-configuration)
  - [HTTPS](#https)
```

Confirm the existing TOC's indentation style first by reading the top of `README.md`; match it.

- [x] **Step 2: Add the HTTPS subsection**

In `README.md`, find the existing `### Graceful shutdown` heading inside `## Server configuration`. Immediately *before* it, insert:

````markdown
### HTTPS

Point the builder at a PEM certificate chain and a PEM PKCS#8 private key:

```java
import java.nio.file.Path;

var server = OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .https(
        Path.of("/etc/letsencrypt/live/example.com/fullchain.pem"),
        Path.of("/etc/letsencrypt/live/example.com/privkey.pem"))
    .build();
```

certbot / Let's Encrypt write exactly these two files to
`/etc/letsencrypt/live/<domain>/`: `fullchain.pem` (your certificate + the
issuing intermediates, concatenated PEM) and `privkey.pem` (unencrypted PKCS#8).
No conversion to PKCS12 / JKS is needed; the library parses the PEM directly
using JDK APIs only.

Both RSA and EC (P-256) private keys are accepted; the algorithm is detected
automatically.

When `.https(...)` is set, the default port changes from `8080` to `8443`.
`port(int)` still overrides explicitly:

```java
OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .https(certChain, privateKey)
    .port(443)              // overrides the 8443 default
    .build();
```

For local development without a real certificate, generate a self-signed pair
with one openssl command:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout privkey.pem -out fullchain.pem \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Clients (browsers, `curl`, `HttpClient`) need to trust the resulting certificate
explicitly — it isn't signed by a public CA.

**Not in this release** (each can land later without breaking the API):

- Encrypted / password-protected private keys
- PKCS12 / JKS keystore inputs
- Certificate hot-reload on renewal (restart the process after `certbot renew`)
- TLS protocol / cipher overrides (JDK defaults apply: TLS 1.2 and 1.3)
- Serving HTTP and HTTPS from one `OpenApiServer` instance
````

- [x] **Step 3: Verify the README renders the new section**

```bash
grep -n "^### HTTPS$" README.md
```

Expected: one line, between the `### Bind address` and `### Graceful shutdown` headings inside `## Server configuration`.

- [x] **Step 4: Commit**

```bash
git add README.md
SKIP=commitlint git commit -m "docs: Document HTTPS support in README"
```

---

## Task 7: Full verification

- [ ] **Step 1: Clean build, all tests**

```bash
mvn clean verify
```

Expected: BUILD SUCCESS. Surefire and Failsafe report no failures. Jacoco report at `target/site/jacoco/` includes the new `PemSslContext` class with full line coverage (every branch is exercised by Task 4's negative tests).

- [ ] **Step 2: Run SonarLint over touched files**

Per the project's pre-push checklist (see `~/.claude/projects/.../memory/feedback_sonar_pre_push.md`), analyse:

- `src/main/java/com/retailsvc/http/internal/PemSslContext.java`
- `src/main/java/com/retailsvc/http/OpenApiServer.java`
- `src/test/java/com/retailsvc/http/internal/PemSslContextTest.java`
- `src/test/java/com/retailsvc/http/OpenApiServerHttpsIT.java`

Fix any new issues raised by SonarLint MCP in the same branch before pushing. NOTE: SonarLint MCP is blind to worktrees (the `/workspace` mount is the main repo); CI scan will cover the branch on push.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin feat/https-support
```

PR opening is manual per the repo's `gh` policy.
