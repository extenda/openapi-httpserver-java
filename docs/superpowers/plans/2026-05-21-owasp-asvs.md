# OWASP ASVS V12 self-assessment + CI gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land a self-assessed OWASP ASVS 5.0 Level 2 (chapter V12, Secure Communication) checklist at `docs/security/owasp-asvs.md`, a README badge linking to it, and a CI gate that forces contributors to re-affirm the checklist whenever they change TLS code.

**Architecture:** Extract `TlsHttpsConfigurator` from a nested class on `OpenApiServer` into its own file under `com.retailsvc.http.internal` so the gate's file-scope predicate is precise. The gate is a bash script invoked from a GitHub Actions workflow, triggered on PRs targeting master. It uses two OR-ed predicates (path-glob over `internal/*(Ssl|Tls|Https)*.java` + import-grep for `javax.net.ssl.*` / `com.sun.net.httpserver.Https*`) to decide whether re-affirmation is required, then checks for a new dated audit-log entry in the checklist file.

**Tech Stack:** Java 25 (refactor only — no logic change), Markdown (checklist + README), GitHub Actions YAML, bash 5+ (gate script).

**Spec:** `docs/superpowers/specs/2026-05-21-owasp-asvs-design.md`

---

## File structure

**New files (production)**

- `src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java` — extracted from the nested class; same code, package-private file, used by `OpenApiServer` only.
- `docs/security/owasp-asvs.md` — the checklist file. Source of truth for TLS controls satisfied / delegated / out-of-scope.
- `.github/workflows/asvs-gate.yml` — GitHub Actions workflow, triggered on PR open/sync/reopen against master.
- `.github/scripts/asvs-gate.sh` — bash gate script. Self-contained; runnable locally for pre-push validation.

**Modified files**

- `src/main/java/com/retailsvc/http/OpenApiServer.java` — remove nested `TlsHttpsConfigurator` + its two imports (`HttpsParameters`, `SSLParameters`); add `import com.retailsvc.http.internal.TlsHttpsConfigurator;`. No behavioural change.
- `README.md` — add one shields.io badge line in the existing badge block at the top.

**No test files added.** The refactor is a pure move; existing `OpenApiServerHttpsIT#negotiatesTls13` already exercises the configurator end-to-end. The gate script is exercised by running it against this branch itself as the smoke test in Task 5.

---

## Task 1: Extract `TlsHttpsConfigurator` to `internal/`

**Files:**

- Create: `src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java`
- Modify: `src/main/java/com/retailsvc/http/OpenApiServer.java` (delete nested class at lines 481–498, adjust imports, add new import)

- [ ] **Step 1: Create the new file**

Write `src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java`:

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

- [ ] **Step 2: Remove the nested class from `OpenApiServer.java`**

In `src/main/java/com/retailsvc/http/OpenApiServer.java`:

Delete the entire block that currently sits at lines 480–498 (the Javadoc comment plus the `private static final class TlsHttpsConfigurator` body, inclusive of its closing `}`). The final `}` on line 499 (closing the outer `OpenApiServer` class) stays.

- [ ] **Step 3: Adjust imports in `OpenApiServer.java`**

Remove these two imports (currently at lines 27 and 44):

```java
import com.sun.net.httpserver.HttpsParameters;
```

```java
import javax.net.ssl.SSLParameters;
```

Add this import (place it alongside the other `com.retailsvc.http.internal.*` imports, alphabetised):

```java
import com.retailsvc.http.internal.TlsHttpsConfigurator;
```

The existing `import com.sun.net.httpserver.HttpsConfigurator;` line stays — it's still needed because `OpenApiServer` references `HttpsConfigurator`'s subclass, no wait, after extraction `OpenApiServer` only references `TlsHttpsConfigurator` (the concrete class). Verify: search the file for any remaining direct reference to `HttpsConfigurator` (the base class). If none, also remove `import com.sun.net.httpserver.HttpsConfigurator;`.

Quick check command:

```bash
grep -n "HttpsConfigurator" src/main/java/com/retailsvc/http/OpenApiServer.java
```

If the only remaining matches are inside the (deleted) line range, also delete the `HttpsConfigurator` import.

- [ ] **Step 4: Run the full test suite**

```bash
mvn clean verify
```

Expected: BUILD SUCCESS. 432 unit tests pass, 55 integration tests pass — identical counts to the pre-refactor branch. `OpenApiServerHttpsIT#negotiatesTls13` still confirms TLS 1.3 negotiation.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/TlsHttpsConfigurator.java \
        src/main/java/com/retailsvc/http/OpenApiServer.java
SKIP=commitlint git commit -m "refactor: Extract TlsHttpsConfigurator to internal package"
```

---

## Task 2: Add `docs/security/owasp-asvs.md`

**Files:**

- Create: `docs/security/owasp-asvs.md`

The directory does not yet exist; this task creates it.

- [x] **Step 1: Create the directory**

```bash
mkdir -p docs/security
```

- [x] **Step 2: Write the checklist file**

Write `docs/security/owasp-asvs.md` with this exact content. The audit-log line is dated 2026-05-21 with a description — no commit SHA, because a commit can't reference its own SHA (amending to insert the SHA would change the SHA again).

````markdown
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

- **2026-05-21** — Initial ASVS 5.0 Level 2 mapping for V12 controls. All listed controls accepted as Implemented / Delegated / N/A / Future as tabulated above.
````

- [x] **Step 3: Commit**

```bash
git add docs/security/owasp-asvs.md
SKIP=commitlint git commit -m "chore: Add OWASP ASVS 5.0 L2 self-assessment for V12"
```

---

## Task 3: Add the CI gate script

**Files:**

- Create: `.github/scripts/asvs-gate.sh` (executable, mode 0755)

- [x] **Step 1: Ensure the scripts directory exists**

```bash
mkdir -p .github/scripts
```

- [x] **Step 2: Write the gate script**

Write `.github/scripts/asvs-gate.sh`:

```bash
#!/usr/bin/env bash
# OWASP ASVS V12 re-affirmation gate.
#
# Fails the PR if TLS-relevant code changed without an updated audit-log
# entry in docs/security/owasp-asvs.md.
#
# TLS-relevance predicate (OR):
#   - changed file matches  src/main/java/com/retailsvc/http/internal/.*(Ssl|Tls|Https).*\.java
#   - any changed .java file under src/main/java/ adds or removes a line
#     importing javax.net.ssl.* or com.sun.net.httpserver.Https*
#
# Local invocation:
#   BASE_SHA=$(git merge-base origin/master HEAD) HEAD_SHA=HEAD .github/scripts/asvs-gate.sh

set -euo pipefail

: "${BASE_SHA:?BASE_SHA env var required}"
: "${HEAD_SHA:?HEAD_SHA env var required}"

CHECKLIST="docs/security/owasp-asvs.md"

changed_files=$(git diff --name-only "$BASE_SHA" "$HEAD_SHA")

tls_paths=$(printf '%s\n' "$changed_files" \
  | grep -E '^src/main/java/com/retailsvc/http/internal/.*(Ssl|Tls|Https).*\.java$' || true)

import_diff=$(git diff -U0 "$BASE_SHA" "$HEAD_SHA" -- 'src/main/java/**/*.java' \
  | grep -E '^[+-]import (javax\.net\.ssl\.|com\.sun\.net\.httpserver\.Https)' || true)

if [ -z "$tls_paths" ] && [ -z "$import_diff" ]; then
  echo "ASVS gate: no TLS-relevant changes."
  exit 0
fi

triggers=""
if [ -n "$tls_paths" ]; then
  triggers+="  (path) $(echo "$tls_paths" | tr '\n' ' ')"$'\n'
fi
if [ -n "$import_diff" ]; then
  triggers+="  (import) $(echo "$import_diff" | head -5)"$'\n'
fi

if ! printf '%s\n' "$changed_files" | grep -qx "$CHECKLIST"; then
  cat >&2 <<EOF
::error title=ASVS V12 gate::TLS-relevant code changed but $CHECKLIST was not updated.

Triggered by:
$triggers
Required action:
  1. Open $CHECKLIST
  2. Confirm each ASVS 5.0 L2 control still holds (update Status / Evidence rows if not)
  3. Append a dated line to ## Audit log, e.g.:
      - **$(date -u +%Y-%m-%d)** — Re-affirmed after change to <file>.java (this PR); all controls hold

This gate exists so TLS changes can't silently drift away from the documented controls.
See $CHECKLIST for the policy.
EOF
  exit 1
fi

added_audit_line=$(git diff "$BASE_SHA" "$HEAD_SHA" -- "$CHECKLIST" \
  | grep -E '^\+- \*\*[0-9]{4}-[0-9]{2}-[0-9]{2}\*\* — ' || true)

if [ -z "$added_audit_line" ]; then
  cat >&2 <<EOF
::error title=ASVS V12 gate::$CHECKLIST was updated but no new audit-log line was added.

Triggered by:
$triggers
The gate requires a new line in the ## Audit log section matching:
  - **YYYY-MM-DD** — <free-text re-affirmation, e.g. "Re-affirmed after change to X.java (this PR); all controls hold">

Touch-only changes to the checklist do not satisfy the gate. The dated line must be added.
EOF
  exit 1
fi

echo "ASVS gate: TLS-relevant changes re-affirmed in $CHECKLIST."
exit 0
```

- [x] **Step 3: Make the script executable**

```bash
chmod 0755 .github/scripts/asvs-gate.sh
```

- [x] **Step 4: Smoke-test the script against the current branch**

The branch will have modified `TlsHttpsConfigurator`-related paths (Task 1 created the new internal file → matches the path-glob predicate), and Task 2 already added an audit-log line. So the gate must say "re-affirmed".

```bash
BASE_SHA=$(git merge-base origin/master HEAD) HEAD_SHA=HEAD .github/scripts/asvs-gate.sh
```

Expected output:

```
ASVS gate: TLS-relevant changes re-affirmed in docs/security/owasp-asvs.md.
```

(Exit code 0.)

- [x] **Step 5: Smoke-test the failure paths via temporary commits**

The gate compares `BASE_SHA..HEAD_SHA`, so we exercise the negative branches by stacking one or two throwaway commits on the current branch and pointing `BASE_SHA` at the right ancestor. Throwaway commits use `-c core.hooksPath=/dev/null` to bypass pre-commit (they never leave the local repo).

**Scenario A — "no checklist update":** one throwaway commit that adds a new TLS-relevant file but doesn't touch the checklist.

```bash
echo "package com.retailsvc.http.internal;" \
  > src/main/java/com/retailsvc/http/internal/TlsProbe.java
git add src/main/java/com/retailsvc/http/internal/TlsProbe.java
git -c core.hooksPath=/dev/null commit -m "tmp-A"
BASE_SHA=HEAD~1 HEAD_SHA=HEAD .github/scripts/asvs-gate.sh
echo "exit=$?"
git reset --hard HEAD~1
rm -f src/main/java/com/retailsvc/http/internal/TlsProbe.java
```

Expected: the script prints the `::error title=ASVS V12 gate::TLS-relevant code changed but docs/security/owasp-asvs.md was not updated.` block to stderr, then `exit=1`. After cleanup, working state is restored.

**Scenario B — "checklist touched but no dated line":** two throwaway commits stacked — one touches a TLS-relevant file, the other touches the checklist with a non-dated change. `BASE_SHA=HEAD~2` makes the diff include both.

```bash
echo "package com.retailsvc.http.internal;" \
  > src/main/java/com/retailsvc/http/internal/TlsProbe.java
git add src/main/java/com/retailsvc/http/internal/TlsProbe.java
git -c core.hooksPath=/dev/null commit -m "tmp-B1"

echo "" >> docs/security/owasp-asvs.md
git add docs/security/owasp-asvs.md
git -c core.hooksPath=/dev/null commit -m "tmp-B2"

BASE_SHA=HEAD~2 HEAD_SHA=HEAD .github/scripts/asvs-gate.sh
echo "exit=$?"

git reset --hard HEAD~2
rm -f src/main/java/com/retailsvc/http/internal/TlsProbe.java
```

Expected: the script prints "$CHECKLIST was updated but no new audit-log line was added" and `exit=1`.

If either scenario unexpectedly passes the gate (exit 0), the script's logic has a hole — STOP and debug before continuing.

- [x] **Step 6: Commit the script**

```bash
git add .github/scripts/asvs-gate.sh
SKIP=commitlint git commit -m "ci: Add ASVS re-affirmation gate script"
```

---

## Task 4: Add the GitHub Actions workflow

**Files:**

- Create: `.github/workflows/asvs-gate.yml`

- [x] **Step 1: Write the workflow**

Write `.github/workflows/asvs-gate.yml`:

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

- [x] **Step 2: Lint the workflow file**

The repo has pre-commit hooks including yamllint. Run them against the new file:

```bash
pre-commit run --files .github/workflows/asvs-gate.yml
```

Expected: all hooks pass.

- [x] **Step 3: Commit**

```bash
git add .github/workflows/asvs-gate.yml
SKIP=commitlint git commit -m "ci: Wire ASVS gate workflow on PRs targeting master"
```

---

## Task 5: Add the README badge

**Files:**

- Modify: `README.md` (insert a badge in the existing badge block at lines 3–7)

- [x] **Step 1: Insert the badge**

Open `README.md`. Find the existing badge block:

```markdown
[![Quality Gate Status](...)](...)
[![Coverage](...)](...)
[![Code Smells](...)](...)
[![Duplicated Lines (%)](...)](...)
[![WorkFlow](...)](...)
```

Immediately after the `WorkFlow` badge line (line 7), insert one new line:

```markdown
[![OWASP ASVS](https://img.shields.io/badge/OWASP_ASVS_5.0-Level_2_V12-blueviolet)](docs/security/owasp-asvs.md)
```

The badge block becomes six badges total. The link uses a relative path so it works on GitHub web, raw clones, and IDE preview.

- [x] **Step 2: Verify the badge renders**

```bash
grep -n "OWASP_ASVS_5.0-Level_2_V12" README.md
```

Expected: one match at line 8.

- [x] **Step 3: Run pre-commit on the modified README**

```bash
pre-commit run --files README.md
```

Expected: all hooks pass (editorconfig, trailing whitespace, end-of-file).

- [x] **Step 4: Commit**

```bash
git add README.md
SKIP=commitlint git commit -m "docs: Add OWASP ASVS badge to README"
```

---

## Task 6: End-to-end verification

- [ ] **Step 1: Full build still green**

```bash
mvn clean verify
```

Expected: BUILD SUCCESS. 432 unit tests, 55 integration tests, zero failures. The refactor in Task 1 is the only code change; identical counts to the base branch.

- [ ] **Step 2: Final gate self-check**

```bash
BASE_SHA=$(git merge-base origin/master HEAD) HEAD_SHA=HEAD .github/scripts/asvs-gate.sh
```

Expected: `ASVS gate: TLS-relevant changes re-affirmed in docs/security/owasp-asvs.md.` (exit 0). This proves the branch passes its own gate when targeted at master.

- [ ] **Step 3: Commit log sanity check**

```bash
git log --oneline master..HEAD
```

Expected: five commits in order

```
<sha> docs: Add OWASP ASVS badge to README
<sha> ci: Wire ASVS gate workflow on PRs targeting master
<sha> ci: Add ASVS re-affirmation gate script
<sha> chore: Add OWASP ASVS 5.0 L2 self-assessment for V12
<sha> refactor: Extract TlsHttpsConfigurator to internal package
```

Plus the spec commit (`c658d1a docs: Design spec for OWASP ASVS V12 self-assessment and CI gate`) and the plan commit you'll create when you commit this plan file.

- [ ] **Step 4: Push the branch**

```bash
git push -u origin chore/owasp-asvs-v9
```

Expected: branch pushed. PR opening is manual per the repo's `gh` policy.

When opening the PR, target `feat/https-support` (stacked PR) — GitHub will auto-retarget to master when the HTTPS branch lands.
