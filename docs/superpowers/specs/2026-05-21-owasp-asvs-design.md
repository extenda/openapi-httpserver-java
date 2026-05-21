# OWASP ASVS V12 self-assessment + re-affirmation gate — design

## Goal

Make the library's TLS posture explicit and durable against silent drift.
Three deliverables, one branch:

1. A self-assessed mapping of the library against **OWASP ASVS 5.0
    Level 2, chapter V12 (Secure Communication)**, committed at
    `docs/security/owasp-asvs.md`. Verbatim control text, per-control
    status, evidence linked to file paths.
2. A README badge that names the standard and links to the checklist.
3. A CI gate that fails any PR which changes TLS code without
    re-affirming the checklist (a dated audit-log entry).

The branch also extracts `TlsHttpsConfigurator` from a nested class on
`OpenApiServer` into its own file under
`com.retailsvc.http.internal`, so the gate's file-scope predicate is
precise.

## Non-goals (v1)

- **No external authority involvement.** The badge says "self-assessed
  against ASVS 5.0 L2"; there is no OWASP certification body. Wording
  must never say "Compliant" or "Certified".
- **No new TLS features.** The ASVS L3 controls (OCSP stapling, ECH)
  are noted in the checklist as "Future" but not implemented here.
- **No cipher allowlist.** V12.1.2 stays at Partial — JDK defaults are
  used. Curating an allowlist is a follow-up.
- **No outbound/client-side coverage.** V12.3 (service-to-service) is
  marked N/A — the library is server-side termination only.
- **No mTLS.** V12.1.3 is N/A — same reason.
- **No automated re-affirmation.** The CI gate enforces *presence* of
  an audit-log line; the line itself is written by the contributor.
  Tempting alternative (auto-bump dates) defeats the "stop and think"
  purpose.

## Public surface

This branch changes **no public API**. The TlsHttpsConfigurator
extraction is internal-package only.

## Deliverable 1: `docs/security/owasp-asvs.md`

### File header

The file's first lines define scope and the immutable wording rule:

```markdown
# OWASP ASVS 5.0 Level 2 — self-assessment

**Standard:** OWASP Application Security Verification Standard, version
5.0.0, chapter V12 (Secure Communication).
**Level:** 2 (typical baseline; consumers needing L3 must layer
additional controls).
**Scope:** Server-side TLS termination via `Builder.https(...)`.
Outbound / service-to-service controls (V12.3) are N/A — the library
makes no outbound TLS connections on the consumer's behalf.

**Wording rules.** This document is a self-assessment; it does NOT
claim certification or compliance. The README badge says "ASVS 5.0
Level 2" only — never "Certified", never "Compliant". OWASP does not
issue conformance certifications.

**Re-affirmation rule.** Any change to TLS-related code (see
`.github/scripts/asvs-gate.sh` for the exact predicate) MUST append a
dated entry to the [Audit log](#audit-log) in the same PR. CI
enforces this. The contributor writes the line — automated bumps are
not accepted.

**Status legend.**
- ✅ Implemented — the library satisfies this control end-to-end
- 🤝 Delegated — consumer must satisfy; see Evidence for guidance
- ⛔ N/A — out of scope for a server-side TLS termination library
- 📋 Future — accepted as a gap; tracked for a follow-up release
```

### Control table

Verbatim ASVS 5.0 V12 controls applicable to this library's scope:

| ID | Control (verbatim, ASVS 5.0) | Level | Status | Evidence |
|---|---|---|---|---|
| 12.1.1 | "Verify that only the latest recommended versions of the TLS protocol are enabled, such as TLS 1.2 and TLS 1.3." | L1 | ✅ Implemented | `TlsHttpsConfigurator` pins `setProtocols({"TLSv1.3","TLSv1.2"})`; verified end-to-end by `OpenApiServerHttpsIT#negotiatesTls13` |
| 12.1.2 | "Verify that only recommended cipher suites are enabled, with the strongest cipher suites set as preferred." | L2 | 📋 Future | We rely on JDK 25 defaults (which exclude RC4, 3DES, EXPORT, NULL, anonymous suites by default). A curated allowlist with explicit preference order is tracked as a follow-up. |
| 12.1.3 | "Verify that the application validates that mTLS client certificates are trusted before using the certificate identity." | L2 | ⛔ N/A | mTLS is not supported in v1. `TlsHttpsConfigurator` explicitly sets `setNeedClientAuth(false)` and `setWantClientAuth(false)`. If mTLS lands later, this row flips to Implemented + new evidence. |
| 12.1.4 | "Verify that proper certification revocation, such as Online Certificate Status Protocol (OCSP) Stapling, is enabled." | L3 | 📋 Future | Out of scope for L2 baseline. Documented so L3-targeting consumers know the gap. |
| 12.1.5 | "Verify that Encrypted Client Hello (ECH) is enabled in the application's TLS settings to prevent exposure of sensitive metadata." | L3 | 📋 Future | Out of scope for L2 baseline. JDK 25 has no stable ECH API. |
| 12.2.1 | "Verify that TLS is used for all connectivity between a client and external facing, HTTP-based services, and does not fall back to insecure communications." | L1 | ✅ Implemented | When `.https(...)` is configured, the server binds `HttpsServer` only; no plaintext fallback listener is created. Mixed-mode (HTTP + HTTPS) is a documented non-goal — operators run two `OpenApiServer` instances if they need both. |
| 12.2.2 | "Verify that external facing services use publicly trusted TLS certificates." | L1 | 🤝 Delegated | The library accepts whatever cert chain the consumer supplies. For production deployments, consumers MUST point `.https(certChain, privateKey)` at a chain signed by a publicly-trusted CA (Let's Encrypt is the recommended source, documented in README §HTTPS). |
| 12.3.1–12.3.5 | (Service-to-service / outbound) | L2 | ⛔ N/A | The library does not initiate outbound TLS on the consumer's behalf. Consumers' outbound HTTP clients are their own responsibility. |

### Per-control supplementary notes

Each 🤝-Delegated row also gets a short prose paragraph after the
table containing a code snippet showing how the consumer satisfies it.
Example for 12.2.2:

> **12.2.2 satisfaction guidance.** Operators should point
> `.https(certChain, privateKey)` at a chain issued by a publicly
> trusted CA. The recommended workflow is certbot / Let's Encrypt
> with the PEM files mounted from a secret manager (see README
> §HTTPS for the full deployment pattern). The library performs no
> chain validation on its own certificate — the server merely
> presents the chain — so it is on the operator to ensure
> publicly-trusted issuance.

Future-status rows do NOT get supplementary code. They get a
one-sentence "accepted gap" note and a link target (filled in when a
follow-up issue is opened; the link can be a literal placeholder
`<issue TBD>` only if no issue exists yet — the gate does NOT
require live links).

### Audit log section

```markdown
## Audit log

- **2026-05-21** — Initial ASVS 5.0 Level 2 mapping for V12 controls (commit `<this commit SHA>`). All listed controls accepted as Implemented / Delegated / N/A / Future as tabulated above.
```

The format is rigid and the gate parses it: `^- \*\*\d{4}-\d{2}-\d{2}\*\* — `. The bash regex used by the gate is `^[+]- \*\*[0-9]{4}-[0-9]{2}-[0-9]{2}\*\* — `.

## Deliverable 2: Refactor `TlsHttpsConfigurator` out of `OpenApiServer`

First commit on this branch. Pure refactor; tests stay green; no API
change.

**Create:** `src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java`

```java
package com.retailsvc.http.internal;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Pins HTTPS to TLS 1.2 and 1.3 only, regardless of operator-level {@code java.security}
 * overrides, and explicitly leaves client-cert auth off (no mTLS in v1).
 */
public final class TlsHttpsConfigurator extends HttpsConfigurator {
  public TlsHttpsConfigurator(SSLContext context) {
    super(context);
  }

  @Override
  public void configure(HttpsParameters params) {
    SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
    sslParams.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    sslParams.setNeedClientAuth(false);
    sslParams.setWantClientAuth(false);
    params.setSSLParameters(sslParams);
  }
}
```

**Modify:** `src/main/java/com/retailsvc/http/OpenApiServer.java`

- Delete the nested `private static final class TlsHttpsConfigurator`
  and its imports of `SSLParameters` / `HttpsParameters`.
- Add `import com.retailsvc.http.internal.TlsHttpsConfigurator;`.
- The single call site `new TlsHttpsConfigurator(sslContext)` is
  unchanged.

Visibility note: the constructor was implicitly private (nested in a
public class with no modifier). After extraction it becomes `public`
inside the `internal` package — same effective access for the only
caller (`OpenApiServer`), which is in the parent package. The
`internal` package is excluded from the published Javadoc and is not
part of the API contract; this matches the existing precedent
(`PemSslContext`, `Router`, `DispatchHandler`).

## Deliverable 3: CI gate

**Create:** `.github/workflows/asvs-gate.yml`

```yaml
name: OWASP ASVS gate

on:
  pull_request:
    branches: [master]
    types: [opened, synchronize, reopened]

permissions:
  contents: read
  pull-requests: read

jobs:
  asvs-checklist:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Enforce ASVS re-affirmation on TLS code changes
        env:
          BASE_SHA: ${{ github.event.pull_request.base.sha }}
          HEAD_SHA: ${{ github.event.pull_request.head.sha }}
        run: .github/scripts/asvs-gate.sh
```

**Create:** `.github/scripts/asvs-gate.sh` (bash, mode 0755).

Logic:

1. `git diff --name-only "$BASE_SHA" "$HEAD_SHA"` → changed file list.
2. **TLS-relevance predicate** (OR-ed):
    - Path match: any changed file matches the regex
      `^src/main/java/com/retailsvc/http/internal/.*(Ssl|Tls|Https).*\.java$`.
    - Import diff: `git diff -U0 "$BASE_SHA" "$HEAD_SHA" -- 'src/main/java/**/*.java'`
      produces any line matching
      `^[+-]import (javax\.net\.ssl\.|com\.sun\.net\.httpserver\.Https)`.
3. If predicate is false → `echo "ASVS gate: no TLS-relevant changes"; exit 0`.
4. If predicate is true and `docs/security/owasp-asvs.md` is NOT in
    the changed file list → fail with the message in the brainstorming
    section. Print the triggering files.
5. If predicate is true and the file IS changed, check the diff of
    `docs/security/owasp-asvs.md` for at least one added line matching
    `^[+]- \*\*[0-9]{4}-[0-9]{2}-[0-9]{2}\*\* —`. If none → fail with a
    message explaining the audit-log line format and showing an example.
6. On success → `echo "ASVS gate: TLS-relevant changes re-affirmed in
    docs/security/owasp-asvs.md"; exit 0`.

### Failure message format

Each failure path prints, to stderr:

```
::error title=ASVS V12 gate::TLS-relevant code changed but docs/security/owasp-asvs.md was not updated.

Triggered by changes to:
  <one path per line>

Required action:
  1. Open docs/security/owasp-asvs.md
  2. Confirm each ASVS 5.0 L2 control still holds (update Status / Evidence rows if not)
  3. Append a dated line to ## Audit log, e.g.:
      - **2026-06-12** — Re-affirmed after change to PemSslContext.java (this PR); all controls hold

This gate exists so TLS changes can't silently drift away from the documented controls.
See docs/security/owasp-asvs.md for the policy.
```

The `::error::` annotation makes it surface in the PR Files-changed
view next to the failing check, not just in the run log.

### Script testability

`asvs-gate.sh` runs cleanly outside CI for local pre-push validation:

```bash
BASE_SHA=$(git merge-base origin/master HEAD) HEAD_SHA=HEAD .github/scripts/asvs-gate.sh
```

Document this invocation in a short header comment at the top of the
script. No bats / shunit2 framework dependency — the logic is shallow
enough that one shell-level smoke test (run on the OWASP branch
itself, which triggers all paths) is sufficient confidence.

## Deliverable 4: README badge

**Modify:** `README.md`.

In the existing badge block (between the Sonar/coverage badges and the
workflow badge), insert one line:

```markdown
[![OWASP ASVS](https://img.shields.io/badge/OWASP_ASVS_5.0-Level_2_V12-blueviolet)](docs/security/owasp-asvs.md)
```

Hover text and the link target both point at the local file
`docs/security/owasp-asvs.md`. Relative path so it works on GitHub
web, raw clones, and IDE preview.

No other README changes in this branch. The HTTPS section already
exists from the previous branch; references to ASVS within it are a
nice-to-have but not required (and would couple the two docs more
than necessary).

## File layout summary

```
.github/
  scripts/
    asvs-gate.sh           [new, executable]
  workflows/
    asvs-gate.yml          [new]
docs/
  security/
    owasp-asvs.md          [new]
README.md                  [modified — one badge line]
src/main/java/com/retailsvc/http/
  OpenApiServer.java       [modified — nested class extracted out]
  internal/
    TlsHttpsConfigurator.java [new]
```

## Order of commits

1. `refactor: Extract TlsHttpsConfigurator to internal package`
2. `chore: Add OWASP ASVS 5.0 L2 self-assessment for V12`
3. `ci: Add ASVS re-affirmation gate for TLS code changes`
4. `docs: Add OWASP ASVS badge to README`

Order matters: the refactor lands first so the gate (commit 3) can be
authored against the new file layout. The badge lands last so the
linked file already exists.

## Acceptance criteria

- `docs/security/owasp-asvs.md` exists, with verbatim ASVS 5.0 V12
  control text, statuses, evidence, audit log with an initial dated
  entry.
- `src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java`
  exists; `OpenApiServer.java` no longer contains a nested
  `TlsHttpsConfigurator`; full test suite (`mvn verify`) is green.
- `.github/workflows/asvs-gate.yml` runs on PR open / sync / reopen.
- `.github/scripts/asvs-gate.sh` is executable, passes its own
  re-affirmation when run against the OWASP branch (which by
  definition both modifies a file in the import-grep predicate set
  *and* updates `docs/security/owasp-asvs.md`).
- README badge renders and the link resolves on the GitHub web UI.
- The OWASP branch passes its own gate when targeted at master.

## Out-of-scope follow-ups (post-v1)

- **Cipher suite allowlist** — flip 12.1.2 to Implemented; curated
  list of TLS 1.3 + TLS 1.2 suites with forward secrecy preferred.
- **mTLS support** — flip 12.1.3 to Implemented; new builder method
  `.requireClientCert(TrustManager)`.
- **OCSP stapling** — flip 12.1.4 (L3) to Implemented; requires
  `SSLEngine` customisation that JDK `HttpsServer` doesn't expose
  cleanly, likely needs a backend adapter (Jetty / Helidon Níma).
- **L3 baseline** — once the L3 controls above land, document a
  parallel L3 self-assessment column or a separate L3-targeted
  checklist file.
- **Cross-version mapping** — if auditors ask for ASVS 4.0.3 mapping,
  add a second column mapping each 5.0 control back to its 4.0.3
  predecessor.
- **Multi-chapter coverage** — V11 (Cryptography) and V13
  (Configuration) have controls the library partially touches; out of
  scope for this branch which targets V12 only.
