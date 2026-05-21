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

## Controls

| ID | Control (verbatim, ASVS 5.0) | Level | Status | Evidence |
|---|---|---|---|---|
| 12.1.1 | "Verify that only the latest recommended versions of the TLS protocol are enabled, such as TLS 1.2 and TLS 1.3." | L1 | ✅ Implemented | `TlsHttpsConfigurator` pins `setProtocols({"TLSv1.3","TLSv1.2"})`; verified end-to-end by `OpenApiServerHttpsIT#negotiatesTls13`. |
| 12.1.2 | "Verify that only recommended cipher suites are enabled, with the strongest cipher suites set as preferred." | L2 | 📋 Future | We rely on JDK 25 defaults (which exclude RC4, 3DES, EXPORT, NULL, anonymous suites by default). A curated allowlist with explicit preference order is tracked as a follow-up. |
| 12.1.3 | "Verify that the application validates that mTLS client certificates are trusted before using the certificate identity." | L2 | ⛔ N/A | mTLS is not supported in v1. `TlsHttpsConfigurator` explicitly sets `setNeedClientAuth(false)` and `setWantClientAuth(false)`. If mTLS lands later, this row flips to Implemented + new evidence. |
| 12.1.4 | "Verify that proper certification revocation, such as Online Certificate Status Protocol (OCSP) Stapling, is enabled." | L3 | 📋 Future | Out of scope for L2 baseline. Documented so L3-targeting consumers know the gap. |
| 12.1.5 | "Verify that Encrypted Client Hello (ECH) is enabled in the application's TLS settings to prevent exposure of sensitive metadata." | L3 | 📋 Future | Out of scope for L2 baseline. JDK 25 has no stable ECH API. |
| 12.2.1 | "Verify that TLS is used for all connectivity between a client and external facing, HTTP-based services, and does not fall back to insecure communications." | L1 | ✅ Implemented | When `.https(...)` is configured, the server binds `HttpsServer` only; no plaintext fallback listener is created. Mixed-mode (HTTP + HTTPS) is a documented non-goal — operators run two `OpenApiServer` instances if they need both. |
| 12.2.2 | "Verify that external facing services use publicly trusted TLS certificates." | L1 | 🤝 Delegated | The library accepts whatever cert chain the consumer supplies. For production deployments, consumers MUST point `.https(certChain, privateKey)` at a chain signed by a publicly-trusted CA. See satisfaction guidance below. |
| 12.3.1 – 12.3.5 | (Service-to-service / outbound) | L2 | ⛔ N/A | The library does not initiate outbound TLS on the consumer's behalf. Consumers' outbound HTTP clients are their own responsibility. |

### Delegated control: satisfaction guidance

**12.2.2.** Operators should point `.https(certChain, privateKey)` at
a chain issued by a publicly trusted CA. The recommended workflow is
certbot / Let's Encrypt with the PEM files mounted from a secret
manager (see README §HTTPS for the full deployment pattern). The
library performs no chain validation on its own certificate — the
server merely presents the chain — so it is on the operator to ensure
publicly-trusted issuance.

## Audit log

- **2026-05-21** — Initial ASVS 5.0 Level 2 mapping for V12 controls (commit `912d410dd0cac911f4ae794d7497c17fac076bab`). All listed controls accepted as Implemented / Delegated / N/A / Future as tabulated above.
