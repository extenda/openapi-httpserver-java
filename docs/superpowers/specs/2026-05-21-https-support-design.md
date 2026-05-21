# HTTPS support — design

## Goal

Let consumers serve their OpenAPI over HTTPS by pointing the builder at PEM
files — the exact files certbot / Let's Encrypt write to disk. No keystore
construction, no PKCS12 conversion, no BouncyCastle.

## Non-goals (v1)

- Encrypted / password-protected private keys (PKCS#8 `EncryptedPrivateKeyInfo`).
  Certbot writes unencrypted PKCS#8 by default; that is the supported shape.
- PKCS12 / JKS keystore inputs. Users with a keystore can convert to PEM with
  `openssl pkcs12 -in keystore.p12 -nokeys -out fullchain.pem` /
  `-nocerts -nodes -out privkey.pem`, or wait for a future
  `.https(SSLContext)` escape hatch.
- HTTP + HTTPS coexistence on one `OpenApiServer`. HTTPS replaces HTTP when
  configured; run two instances if you need both.
- Hot reload on certificate rotation. Renewal → restart the process.
- TLS protocol / cipher overrides. JDK defaults (TLS 1.2 + 1.3).
- Classpath / `InputStream` inputs. `Path` only, consistent with
  `Spec.fromPath(Path)`.

Each of these can be added later without breaking the v1 API.

## Public API

One new method on `OpenApiServer.Builder`:

```java
public Builder https(Path certificateChainPem, Path privateKeyPem)
```

- `certificateChainPem` — server certificate followed by any intermediates,
  concatenated PEM. Matches certbot's `fullchain.pem`.
- `privateKeyPem` — unencrypted PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`).
  Matches certbot's `privkey.pem`. Both RSA and EC keys are accepted.

Both arguments are required when the method is called; either being `null`
fails fast with `NullPointerException` at builder time.

### Port behaviour

- Default port flips to `8443` when `.https(...)` is set; stays `8080`
  otherwise.
- `Builder.port(int)` overrides the default as today, including `0` for an
  ephemeral port.
- `OpenApiServer.listenPort()` returns whatever was actually bound.

### Failure model

All HTTPS setup failures surface as `IllegalStateException` from `build()`
with a message naming the file and the specific problem:

| Cause                                        | Message shape                                                   |
| -------------------------------------------- | --------------------------------------------------------------- |
| File missing / unreadable                    | `Cannot read TLS certificate chain: <path>`                     |
| Certificate PEM malformed                    | `Failed to parse TLS certificate chain from <path>`             |
| Private key PEM malformed                    | `Failed to parse TLS private key from <path>`                   |
| Key algorithm neither RSA nor EC             | `Unsupported TLS private key algorithm in <path>`               |
| Cert/key mismatch (KeyManagerFactory rejects)| `TLS certificate and private key do not match`                  |

The original cause is chained.

## Internals

### New class: `com.retailsvc.http.internal.PemSslContext`

Package-private, single static entry point:

```java
final class PemSslContext {
  static SSLContext load(Path certChainPem, Path privateKeyPem);
}
```

Steps:

1. Read all bytes of `certChainPem`. Feed to
    `CertificateFactory.getInstance("X.509").generateCertificates(in)` →
    `Collection<? extends Certificate>` → `Certificate[]`. The JDK handles
    concatenated PEM natively, so no manual splitting is required.
2. Read `privateKeyPem` as UTF-8. Strip `-----BEGIN PRIVATE KEY-----`,
    `-----END PRIVATE KEY-----`, and all whitespace. Base64-decode the
    remainder into a `byte[]` and wrap in `PKCS8EncodedKeySpec`.
3. Recover the `PrivateKey`: try `KeyFactory.getInstance("RSA")
    .generatePrivate(spec)`; on `InvalidKeySpecException` try `"EC"`. If both
    fail, throw `IllegalStateException` with the "unsupported algorithm"
    message.
4. Build an in-memory keystore: `KeyStore ks = KeyStore.getInstance("PKCS12");
    ks.load(null, null); ks.setKeyEntry("server", key, new char[0], chain);`.
5. Initialise key managers: `KeyManagerFactory kmf =
    KeyManagerFactory.getInstance("SunX509"); kmf.init(ks, new char[0]);`. A
    mismatch between key and cert surfaces here and is translated to the
    "do not match" message.
6. `SSLContext ctx = SSLContext.getInstance("TLS"); ctx.init(kmf.getKeyManagers(),
    null, null); return ctx;`

Each step catches the narrowest checked / runtime exception it can produce
and rethrows `IllegalStateException` with the message table above. No
`Throwable` catch-alls.

### Wiring in `OpenApiServer`

Two new fields on `Builder`:

```java
private Path httpsCertChain;
private Path httpsPrivateKey;
```

Set by `.https(...)`. Default port resolution in `build()`:

```java
int resolvedPort = port != null ? port : (httpsCertChain != null ? 8443 : 8080);
```

(`port` becomes `Integer` so we can distinguish "user set it" from "use
default". This is an internal refactor; the public `port(int)` signature is
unchanged.)

Server creation:

```java
HttpServer server;
if (httpsCertChain != null) {
  SSLContext sslContext = PemSslContext.load(httpsCertChain, httpsPrivateKey);
  HttpsServer https = HttpsServer.create(new InetSocketAddress(host, resolvedPort), 0);
  https.setHttpsConfigurator(new HttpsConfigurator(sslContext));
  server = https;
} else {
  server = HttpServer.create(new InetSocketAddress(host, resolvedPort), 0);
}
```

`HttpsServer extends HttpServer`, so every existing call site — context
registration, executor wiring, filters, extra routes, shutdown — is
untouched.

## Tests

### Unit: `PemSslContextTest`

Fixtures under `src/test/resources/tls/`:

- `rsa-cert.pem`, `rsa-key.pem` — self-signed RSA cert + PKCS#8 key
- `ec-cert.pem`, `ec-key.pem` — self-signed EC (P-256) cert + PKCS#8 key
- `mismatched-key.pem` — RSA key that does not match `rsa-cert.pem`
- `garbage.pem` — random bytes inside PEM headers

Generated once via `openssl req -newkey rsa:2048 -x509 -days 3650 -nodes ...`
(and `-newkey ec:<(openssl ecparam -name prime256v1)` for EC), committed to
the repo. These are test fixtures, not secrets.

Cases:

- RSA happy path → non-null `SSLContext`, key managers initialised.
- EC happy path → non-null `SSLContext`.
- Missing cert file → `IllegalStateException` with "Cannot read" message.
- Missing key file → ditto.
- Garbage cert PEM → "Failed to parse TLS certificate chain".
- Garbage key PEM → "Failed to parse TLS private key".
- Mismatched cert + key → "do not match".

### Integration: `OpenApiServerHttpsIT`

Boots an `OpenApiServer` on port `0` with `.https(rsaCert, rsaKey)` and a
single handler. Builds an `HttpClient` whose `SSLContext` trusts only the
test certificate, sends `GET /…`, asserts `200` + expected body. Mirrors the
shape of `OpenApiServerIT`. Repeated for the EC fixture so we exercise both
algorithms end-to-end.

### Negative integration

Build-time failures (`IllegalStateException` thrown from `.build()`) for:

- non-existent cert path
- mismatched cert/key

The unit tests already cover most error paths; these two confirm the
exception propagates through the builder.

## Documentation

New `### HTTPS` subsection in `README.md` under `## Server configuration`,
placed immediately before `### Graceful shutdown`. Content:

1. The `.https(certChain, privateKey)` call with a code sample.
2. A short paragraph noting certbot's `fullchain.pem` + `privkey.pem` map
    directly onto the two arguments — no conversion needed.
3. The port-default note (8443 when HTTPS, 8080 otherwise; `port(int)`
    overrides).
4. A `openssl req -newkey rsa:2048 -nodes -keyout privkey.pem -x509 -days
    365 -out fullchain.pem -subj "/CN=localhost"` one-liner for local
    self-signed dev certs, with the caveat that browsers/clients need to
    trust it explicitly.
5. The non-goals list as a short "Not in this release" bullet list so users
    aren't surprised: no encrypted keys, no keystore inputs, no hot reload,
    no TLS-config knobs, no HTTP+HTTPS coexistence.

Table of contents updated; subsection cross-link added next to `bindAddress`
where appropriate.

## Out of scope follow-ups (post-v1)

These are flagged here so we don't paint ourselves into a corner. The v1
API leaves room for each:

- `.https(SSLContext)` overload for mTLS, custom trust managers, or keys
  loaded from Vault / KMS.
- Encrypted PKCS#8 support via a `char[] password` overload of `.https(...)`.
- Cert hot reload: a `WatchService` on the PEM directory swapping the
  `SSLContext` inside a wrapping `HttpsConfigurator.configure(HttpsParameters)`.
- Dual binding (HTTP + HTTPS on different ports in one server).
