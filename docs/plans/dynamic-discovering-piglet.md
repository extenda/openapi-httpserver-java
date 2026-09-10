# gzip content encoding

## Context

The library has no notion of `Content-Encoding` or `Accept-Encoding` today — a repo-wide grep
returns zero hits. Every request body is read verbatim with `exchange.getRequestBody().readAllBytes()`
and every response body is written verbatim by `ResponseRenderer`. A client that gzips its payload
gets a schema-validation failure on binary garbage, and JSON responses always go out uncompressed
even when the client advertises gzip support.

This adds transparent gzip in both directions:

- **Requests** — a body sent with `Content-Encoding: gzip` is inflated before OpenAPI body
  validation runs, so the validator, the `TypeMapper`s and handlers see plain bytes and need no
  changes. Inflation is bounded so a small compressed payload cannot expand into an OOM (zip bomb).
- **Responses** — a body is gzipped when the client sends `Accept-Encoding: gzip`, the media type is
  text-shaped, and the payload clears a size threshold.

Both directions are on by default; the two numeric knobs are builder options.

## Design decisions

| Decision | Choice |
|---|---|
| Enablement | On by default, no on/off flag. Raising `minimumGzipResponseBytes` is the escape hatch. |
| Codings | `gzip` (and the legacy `x-gzip` alias) only. `identity` is a legal no-op, not an error. |
| Unknown request coding (`br`, `deflate`, two stacked codings) | `415 Unsupported Media Type` |
| Malformed / truncated gzip stream | `400 Bad Request`, problem+json, `ZipException` as cause |
| Over the inflation cap | `413 Content Too Large` |
| `Accept-Encoding` absent, or `gzip;q=0` | No compression. `*` with q>0 does compress. |
| Response policy | Compressible media type **and** body ≥ threshold (default 1 KiB) |
| Inflation cap | Default 10 MiB, `maxDecompressedRequestBytes(long)` |
| Uncompressed request bodies | Stay unbounded, as today — pre-existing gap, out of scope |
| Streaming responses | Compressed too; `BodyWriter.Sized` degrades to chunked |
| `Request.header("Content-Encoding")` | Hidden after decoding; `Content-Length` reports the *inflated* length |
| `SecurityFilter` 401/403 | Left uncompressed — bypasses the renderer, bodies are ~120 bytes |

## Branch

`feat/gzip-content-encoding`, cut from `master`. A plain branch, not a worktree — the Java LSP and
the SonarLint MCP server are both blind to `.claude/worktrees/`.

---

## Architecture

Two shared, immutable collaborators built once in `OpenApiServer` and threaded into the filters,
exactly as `ResponseRenderer` is threaded today from `OpenApiServer.java:95`:

- `RequestBodyReader` (new) — inbound decode, owns the inflation cap.
- `ResponseRenderer` (existing) — outbound encode, gains the size threshold.

Putting the encode step inside `ResponseRenderer.render` covers five of the six response paths:
normal dispatch, handler exceptions, pre-request 404/405/400, extras routes (`/health`, spec
serving, CORS preflight) and the catch-all `/` 404. The sixth — `SecurityFilter.renderRejection`
(`SecurityFilter.java:111-137`) — writes to the exchange directly and stays uncompressed by design.

A `ResponseDecorator` would have been the wrong hook: decorators run only in `DispatchHandler`
(`DispatchHandler.java:47-49`), so every error body, the health endpoint and all 404s would be missed.

## New files

### `internal/AcceptEncodingHeader.java`

```java
/** Parses {@code Accept-Encoding} request header values (RFC 9110 §12.5.3). */
public final class AcceptEncodingHeader {
  public static boolean acceptsGzip(String header);
}
```

Split on `,`; per token strip `;` params; lowercase. `q` defaults to `1.0`, and an unparsable `q` is
treated as `1.0` (lenient — no security consequence). Track `gzipQ` (from `gzip` or `x-gzip`) and
`starQ`; return `gzipQ != null ? gzipQ > 0 : starQ != null && starQ > 0`. An explicit `gzip;q=0` is a
refusal and beats a positive `*`.

### `internal/ContentEncodingHeader.java`

```java
/** Classifies a request {@code Content-Encoding} into the codings the server can decode. */
public final class ContentEncodingHeader {
  public enum Coding { NONE, GZIP, UNSUPPORTED }
  public static Coding parse(String header);
}
```

`null`/blank/`identity` → `NONE`; a single `gzip`/`x-gzip` (optionally with `identity`) → `GZIP`;
anything else, including two real stacked codings → `UNSUPPORTED`.

### `internal/RequestBodyReader.java`

```java
/** Reads the request body, transparently inflating gzip under a hard cap on decompressed size. */
public final class RequestBodyReader {
  public static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 10L * 1024 * 1024;

  public RequestBodyReader(long maxDecompressedBytes);   // IllegalArgumentException if <= 0
  public Body read(HttpExchange exchange) throws IOException;

  public record Body(byte[] bytes, boolean decoded) {
    public UnaryOperator<String> headerLookup(Headers headers);
  }
}
```

Read the raw bytes first, then inflate from a `ByteArrayInputStream`. Inflating the exchange stream
directly would make `new GZIPInputStream(...)` throw `EOFException` on an empty body, forcing a peek
to tell "no body" from "truncated body". Peak memory is compressed + inflated, and the plain path
already buffers the whole body.

Inflate in a loop with a running counter; trip the cap **mid-inflate** rather than after, so a bomb
never materialises. Catch **only `ZipException | EOFException`** and rethrow as
`BadRequestException(HTTP_BAD_REQUEST, "malformed gzip request body", e)` — a genuine socket
`IOException` must stay an `IOException` and render 500, not 400.

`headerLookup` returns `headers::getFirst` untouched when nothing was decoded. After inflating it
hides `Content-Encoding` and reports the inflated `Content-Length`: the stored value is the
*compressed* size, which would be a lie sitting next to `bytes()`.

`BadRequestException` already enforces a 4xx status (`BadRequestException.java:47`) and
`Handlers.defaultExceptionHandler()` (`Handlers.java:67-77`) renders it as problem+json with the
supplied status and logs the cause at DEBUG — 413 and 415 need no new error plumbing.

### `internal/ResponseCompression.java`

```java
/** Response content-coding policy and the gzip primitives the renderer uses. */
public final class ResponseCompression {
  public static boolean isCompressible(String contentType);
  public static byte[] gzip(byte[] body) throws IOException;
  public static OutputStream gzipStream(OutputStream out) throws IOException;
}
```

`text/*` **except `text/event-stream`** (gzip buffers through a `Deflater`; wrapping SSE destroys
per-event flushing and hangs the client), the `+json` / `+xml` / `+yaml` structured suffixes, plus
`application/json`, `application/xml`, `application/yaml`, `application/x-yaml`,
`application/javascript`, `application/x-ndjson`. `application/problem+json` and `image/svg+xml` fall
out of the suffix rules; `application/octet-stream` — the `byte[]` default — is excluded.

**Null guard is load-bearing:** `ContentTypeHeader.mediaType(null)` returns `application/json`, so a
null content type must be rejected *before* that call, or every untyped stream looks like JSON.

## Modified files

### `internal/ResponseRenderer.java` — the bulk of the work

Two-arg constructor `(Map<String, TypeMapper>, long minimumGzipBytes)` plus a retained 1-arg
overload delegating to it with `DEFAULT_MINIMUM_GZIP_BYTES = 1024`, so the three existing test
call sites compile untouched.

`render` gains a third branch so the null-body case can still do header work:
`renderEmpty` / `renderStream` / `renderBytes`.

Three private helpers:

- `bodyAllowed(int status)` — false for 1xx, 204, 205, 206, 304. Those must never carry a coding.
- `wouldCompress(exchange, headers, length)` — no existing `Content-Encoding`, `length >= threshold`,
  and `AcceptEncodingHeader.acceptsGzip(...)`.
- `addVary(headers)` — appends `Accept-Encoding` to any existing `Vary` (the CORS preflight handler
  already emits `Vary: Origin`) as one merged field line, skipping if `*` or `Accept-Encoding` is
  already listed.

`renderBytes` inserts one `maybeCompress` call between the Content-Type default and
`sendResponseHeaders`; `Content-Length` stays correct for free because `:71` derives it from
`bytes.length`. If gzip comes out no smaller than the input, keep the original bytes and set no
header.

`renderStream` threshold-checks `BodyWriter.Sized.length()`; a `Chunked` body has no knowable length
and maps to "always over threshold" — buffering to measure would defeat streaming. When compressing,
send `sendResponseHeaders(status, 0)` (chunked), because `Sized.length()` is the *uncompressed*
length and must not go on the wire.

`renderEmpty` is where the HEAD fix lands: when a GET would have been compressed, drop the
hand-declared `Content-Length` (RFC 9110 §9.3.2 permits omitting fields "determined only while
generating the content"). Never set `Content-Encoding` on a bodiless response — it would promise an
encoding for a body the client may later fetch with a different `Accept-Encoding`.

**Ordering constraint:** check `isCompressible(contentType) && bodyAllowed(status)` before reading
`exchange.getRequestHeaders()`. Cheap checks first anyway — but see the test-stub note in Task 7.

### `internal/RequestPreparationFilter.java`

New field + 8th constructor parameter `RequestBodyReader bodyReader` (the constructor already carries
`@SuppressWarnings("java:S107")`). At `:97`, `bodyReader.read(exchange)`; at `:124`,
`body.headerLookup(headers)` in place of `headers::getFirst`. The read stays before routing, so
404/405 behaviour is unchanged. No exception wiring: `BadRequestException` is a `RuntimeException`
and `doFilter`'s catch at `:72-76` already renders it.

### `internal/ExtrasRouter.java`

Same substitution at `:58` and `:68`, plus a 3rd constructor parameter.

### `internal/ProblemDetail.java`

`TITLES` has no 413 entry, so a 413 would render `"title": "Bad Request"`. Add
`413 -> "Content Too Large"`. **`Map.of` caps at 10 pairs and `TITLES` has exactly 10** — the 11th
forces a rewrite to `Map.ofEntries(entry(...), ...)`.

### `Handlers.java`

**No change.** The HEAD fix lives in `renderEmpty`, which fixes *any* handler that hand-sets
`Content-Length` on a null body, not just `resourceHandler`.

### `OpenApiServer.java`

`HandlerConfig` (`:61-67`) gains `long maxDecompressedRequestBytes, long minimumGzipResponseBytes`.
Builder fields default from the two internal constants; two fluent setters modelled on
`shutdownTimeoutSeconds` (`:403-410`) — `maxDecompressedRequestBytes` requires `> 0`,
`minimumGzipResponseBytes` requires `>= 0`. At `:95`, build both collaborators and thread
`bodyReader` through `wireBindings` → `wireBinding` and `wireExtras`, parallel to `renderer`.

---

## Tasks

Test-first throughout: write the named tests, watch them fail, then implement. Check off each step
as it completes.

### Task 1 — header parsing

- [x] **Step 1** `internal/AcceptEncodingHeaderTest` — `nullHeaderIsNotAccepted`,
      `blankHeaderIsNotAccepted`, `plainGzipIsAccepted`, `gzipAmongOtherCodingsIsAccepted`,
      `caseInsensitiveGzipIsAccepted`, `xGzipIsAccepted`, `explicitZeroQValueIsRefused`,
      `positiveQValueIsAccepted`, `wildcardIsAccepted`, `wildcardWithZeroQValueIsRefused`,
      `explicitGzipBeatsWildcardRefusal`, `identityOnlyIsNotAccepted`,
      `malformedQValueIsTreatedAsAccepted`. Then implement.
- [x] **Step 2** `internal/ContentEncodingHeaderTest` — `nullHeaderIsNone`, `emptyHeaderIsNone`,
      `identityIsNone`, `gzipIsGzip`, `xGzipIsGzip`, `mixedCaseGzipIsGzip`, `gzipWithIdentityIsGzip`,
      `brotliIsUnsupported`, `deflateIsUnsupported`, `stackedCodingsAreUnsupported`. Then implement.

### Task 2 — bounded request inflation

- [x] **Step 3** `internal/RequestBodyReaderTest` (mocked `HttpExchange`, per the
      `ExtrasRouterTest` pattern) — `plainBodyIsReturnedUnchanged`, `gzipBodyIsInflated`,
      `emptyGzipBodyIsReturnedEmpty`, `unsupportedCodingThrows415`, `oversizedInflatedBodyThrows413`,
      `malformedGzipThrows400WithCause`, `truncatedGzipThrows400`,
      `decodedBodyHidesContentEncodingHeader`, `decodedBodyReportsInflatedContentLength`,
      `plainBodyKeepsOriginalHeaderLookup`, `constructorRejectsNonPositiveCap`. Then implement.
- [x] **Step 4** `internal/ProblemDetailTest` — `contentTooLargeHasItsOwnTitle`. Then convert
      `TITLES` to `Map.ofEntries` and add the 413 row.
- [x] **Step 5** Wire into `RequestPreparationFilter` and `ExtrasRouter`:
      `gzipRequestBodyIsInflatedBeforeValidation`, `unsupportedRequestCodingIsRejectedBeforeRouting`,
      `gzipRequestBodyIsInflatedForExtraRoutes`. Their test factories gain a `RequestBodyReader`
      argument — the compile break is the expected first failure.

### Task 3 — response compression

- [x] **Step 6** `internal/ResponseCompressionTest` — `nullContentTypeIsNotCompressible`,
      `jsonIsCompressible`, `problemJsonIsCompressible`, `yamlIsCompressible`,
      `textPlainWithCharsetIsCompressible`, `xmlSuffixIsCompressible`,
      `octetStreamIsNotCompressible`, `imagePngIsNotCompressible`, `eventStreamIsNotCompressible`,
      `gzipRoundTripsBytes`, `gzipStreamRoundTripsBytes`. Then implement.
- [x] **Step 7** `internal/ResponseRendererTest` — the repo's **first direct renderer test**, so it
      starts with baseline coverage of behaviour it is about to change
      (`writesBytesWithContentLength`, `writesNullBodyWithMinusOne`, `writesSizedStreamWithLength`),
      then: `compressesJsonBodyOverThreshold`, `setsContentEncodingGzipWhenCompressed`,
      `sentContentLengthMatchesCompressedPayload`, `skipsCompressionBelowThreshold`,
      `skipsCompressionForOctetStream`, `skipsCompressionWithoutAcceptEncoding`,
      `skipsCompressionWhenGzipRefusedByQValue`,
      `skipsCompressionWhenHandlerAlreadySetContentEncoding`, `addsVaryEvenWhenNotCompressed`,
      `appendsVaryToExistingValue`, `doesNotDuplicateVary`, `neverCompressesNoContentResponses`,
      `fallsBackToPlainBytesWhenGzipIsLarger`, `leavesUncompressedBodyByteIdentical`.
      Then implement `bodyAllowed`, `wouldCompress`, `addVary`, `maybeCompress`.

      Add `when(exchange.getRequestHeaders()).thenReturn(new Headers())` to
      `DispatchHandlerTest.stubExchange()` — it stubs only `getResponseHeaders()` today, so an
      unstubbed `getRequestHeaders()` returns null. Fixing the stub is honest and removes a fragile
      dependency on evaluation order in main code.
- [x] **Step 8** Streaming and null bodies, same test class:
      `compressesChunkedStreamWhenAcceptEncodingPresent`,
      `degradesSizedStreamToChunkedWhenCompressed`, `skipsCompressionForSizedStreamBelowThreshold`,
      `skipsCompressionForNullContentTypeStream`,
      `stripsContentLengthOnNullBodyWhenGetWouldCompress`,
      `keepsContentLengthOnNullBodyWhenClientDoesNotAcceptGzip`,
      `keepsContentLengthOnNullBodyForNonCompressibleType`.
      Then implement `renderStream` and `renderEmpty`.

### Task 4 — builder

- [x] **Step 9** `OpenApiServerBuilderTest` — `maxDecompressedRequestBytesRejectsZero`,
      `maxDecompressedRequestBytesRejectsNegative`, `minimumGzipResponseBytesRejectsNegative`.
      Then add the two setters, the `HandlerConfig` fields and the `build()` wiring.

### Task 5 — end to end

- [x] **Step 10** `GzipIT` extending `ServerBaseTest`. Reuses the existing `/openapi.json` fixture
      with runtime handler overrides — **no new spec files**. `text-echo` (`POST /text-echo`,
      `text/plain`, schema `{"type":"string"}`, no `maxLength`) echoes the body, so one call
      exercises both directions. Private `gzip(byte[])` / `gunzip(byte[])` helpers; requests built
      manually as in `NonJsonBodyIT`; responses read with `BodyHandlers.ofByteArray()`. Tests:
      `largeJsonResponseIsGzippedWhenClientAcceptsGzip`, `smallJsonResponseIsNotGzipped`,
      `responseIsNotGzippedWithoutAcceptEncoding`, `streamedSpecResourceIsGzippedAndChunked`,
      `headOmitsContentLengthWhenGetWouldBeCompressed`,
      `headKeepsContentLengthWithoutAcceptEncoding`, `gzippedRequestBodyIsDecompressed`,
      `handlerDoesNotSeeContentEncodingHeader`, `unsupportedRequestEncodingReturns415`,
      `malformedGzipRequestReturns400`, `oversizedGzipRequestReturns413` (server built with
      `maxDecompressedRequestBytes(1024)`, posting ~4 KiB gzipped — keeps it fast and covers the
      builder option).

      `java.net.http.HttpClient` neither sends `Accept-Encoding` nor auto-decompresses, so
      `responseIsNotGzippedWithoutAcceptEncoding` is the regression guard for the whole existing
      IT suite.

### Task 6 — docs

- [ ] **Step 11** README: `### Request decompression` and `### Response compression` under
      `## Server configuration`, a TOC entry, a `## Highlights` bullet, and a **"Not in this
      release"** list matching the HTTPS section's convention — brotli/deflate/zstd,
      `Accept-Encoding` on 415 responses (RFC 9110 §15.5.16 SHOULD; `BadRequestException` carries no
      headers), compression of `SecurityFilter` 401/403 bodies, per-route opt-out. Plus `Caveats`
      bullets: the cap bounds *inflated* bytes only and is not a request size limit; a strong `ETag`
      set by a handler now spans two byte streams; a handler that throws mid-stream yields a valid
      gzip trailer over truncated content rather than a framing error.
- [ ] **Step 12** Correct the stale request-flow description in `CLAUDE.md` — it describes three
      filters including `ExceptionFilter` on the spec context, exchange-attribute body stashing and
      a `Request.bytes(exchange)` static helper, none of which match the current code, and it
      mentions neither `SecurityFilter` nor `ExtrasRouter`.

---

## Verification

```bash
mvn test
mvn test -Dtest=ResponseRendererTest
mvn verify                      # Failsafe runs GzipIT; JaCoCo at target/site/jacoco/
mvn verify -Dit.test=GzipIT
```

What to look for:

- **Zero regressions in the pre-existing ITs.** This is the load-bearing signal that on-by-default
  compression is invisible to `java.net.http.HttpClient`. If `OpenApiServerIT`, `SecurityIT`,
  `NonJsonBodyIT` or `ExtraHandlersIT` change behaviour, the gate is wrong.
- No JUL warning `sendResponseHeaders: being invoked with a content length for a HEAD request` in
  the Failsafe output — that string means a HEAD request reached `renderBytes`.
- `target/site/jacoco/` — the four new internal classes near-fully covered, and `ResponseRenderer`
  coverage sharply up now that it has a direct test.

Manually against the example server:

```bash
mvn test-compile exec:java -Dexec.mainClass=com.retailsvc.http.start.ServerLauncher \
    -Dexec.classpathScope=test

# small body: Vary only, no Content-Encoding
curl -sD- -o /dev/null -H 'Accept-Encoding: gzip' http://localhost:8080/api/v1/data

# request decompression — expect the echoed text back
printf 'hello gzip' | gzip | curl -s --data-binary @- \
    -H 'Content-Type: text/plain' -H 'Content-Encoding: gzip' \
    http://localhost:8080/api/v1/text-echo

# unsupported coding — expect 415 problem+json
curl -si -H 'Content-Encoding: br' -H 'Content-Type: text/plain' \
    --data-binary 'x' http://localhost:8080/api/v1/text-echo
```

`k6 run acceptance/k6/script.js` should pass unchanged: it asserts
`r.headers['Content-Type'] === 'application/json'`, which compression never touches, and every
payload it exercises is far under 1 KiB.

`mvn sortpom:sort` is not needed — no new dependencies, `java.util.zip` is in `java.base`.

Before pushing: analyse every touched file with the SonarLint MCP server and fix any new issue in
the same branch. Watch for `java:S6218` on the `Body` record (array component) and `java:S107` on the
widened `HandlerConfig`.
