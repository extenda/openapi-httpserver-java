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
