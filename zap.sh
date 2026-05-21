#!/usr/bin/env bash
# Run a ZAP API scan against a locally running OpenAPI server.
# Prerequisite: the example server must be listening on :8080, e.g.
#   mvn test-compile exec:java \
#     -Dexec.mainClass=com.retailsvc.http.start.ServerLauncher \
#     -Dexec.classpathScope=test
set -euo pipefail

docker run --rm -v "$(pwd)":/zap/wrk/:rw -t ghcr.io/zaproxy/zaproxy:stable zap-api-scan.py \
    -t src/test/resources/openapi.json \
    -f openapi \
    -O http://host.docker.internal:8080 \
    -s \
    -g gen.conf \
    -r zap-report.html
