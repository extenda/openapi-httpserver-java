docker run -v "$(pwd)":/zap/wrk/:rw -t ghcr.io/zaproxy/zaproxy:stable zap-api-scan.py \
    -t src/test/resources/openapi.json \
    -P 8080 \
    -f openapi \
    -s -g gen.conf \
    -r zap-report.html \
    -t http://host.docker.internal:8080
