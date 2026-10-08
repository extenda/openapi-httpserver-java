# Streaming Request Bodies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a handler opt into reading the request body as it arrives, via a `StreamingRequestHandler` marker type, so memory per request no longer grows with the body — while routing, parameter validation, security, interceptors and error rendering keep working.

**Architecture:** `RequestPreparationFilter` routes before reading the body. For an operation whose handler is a `StreamingRequestHandler`, it opens the body through a new `RequestBodyReader.stream(...)` instead of `read(...)`, validates parameters, peeks one byte to apply the `required` / `Content-Type` checks, and binds a `Request` built with `Request.streaming(...)`. Coded bodies are decoded lazily by an internal `DecodingInputStream` that enforces the decompression cap as the handler reads (`maxDecompressedStreamingRequestBytes`, defaulting to `maxDecompressedRequestBytes`; Task 15). `ExtrasRouter` takes the same split for extra routes. Buffered operations keep their exact behaviour and order.

**Additions (Tasks 9–12), review fixes (Task 13):** Content-Type matching with media-type ranges and a missing-header default, `Request.headers()`, raw (undecoded) streaming bodies, and a server-wide cap on concurrent streaming requests. See the spec's "Additions" section.

**Tech Stack:** Java 25, JDK `com.sun.net.httpserver`, JUnit 5, AssertJ, Mockito, Maven.

**Spec:** `docs/superpowers/specs/2026-10-08-streaming-request-bodies-design.md`

---

## File Structure

**Create:**
- `src/main/java/com/retailsvc/http/StreamingRequestHandler.java` — public marker interface extending `RequestHandler`.
- `src/main/java/com/retailsvc/http/internal/DecodingInputStream.java` — lazy, capped decoder over the raw body.
- `src/test/java/com/retailsvc/http/internal/DecodingInputStreamTest.java` — decoder unit tests.
- `src/test/java/com/retailsvc/http/StreamingRequestBodyIT.java` — end-to-end behaviour with a live `OpenApiServer`.
- `src/test/resources/streaming-openapi.json` — dedicated fixture spec (keeps `/openapi.json` and `ServerLauncher` untouched).
- `src/main/java/com/retailsvc/http/internal/RequestContentType.java` — media-type matching against `requestBody.content`.
- `src/main/java/com/retailsvc/http/internal/StreamingLimit.java` — the concurrency cap and its 503.
- `src/test/java/com/retailsvc/http/internal/RequestContentTypeTest.java`, `StreamingLimitTest.java`, `src/test/java/com/retailsvc/http/StreamingRequestHandlerTest.java`.

**Modify:**
- `src/main/java/com/retailsvc/http/Request.java` — stream field, `streaming(...)` factory, `bodyStream()`, `isStreaming()`, buffered accessors guarded.
- `src/main/java/com/retailsvc/http/internal/RequestBodyReader.java` — `stream(HttpExchange)` and `Streamed` record; shared decoded-header view.
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — streaming-operation set, route-first branch, `buildStreamingRequest`.
- `src/main/java/com/retailsvc/http/internal/ExtrasRouter.java` — streamed vs buffered request for the matched extra.
- `src/main/java/com/retailsvc/http/internal/SpecBinding.java` — `streamingOperations()`.
- `src/main/java/com/retailsvc/http/OpenApiServer.java` — pass streaming set into the filter; boot validation; javadoc.
- `src/test/java/com/retailsvc/http/RequestTest.java`, `src/test/java/com/retailsvc/http/internal/RequestBodyReaderTest.java` — unit tests.
- `README.md` — "Streaming request bodies" section, TOC, highlights, content-encoding note, caveat.

**Untouched but worth a glance:**
- `SecurityFilter` — calls `withPrincipals`; the stream must survive it.
- `DispatchHandler` — interceptors and decorators wrap streaming handlers unchanged.
- `ResponseRenderer` — closes the exchange; the JDK drains at most 64 KiB of an unread body.

---

## Task 1: `StreamingRequestHandler` marker

**Files:** Create `src/main/java/com/retailsvc/http/StreamingRequestHandler.java`

- [x] **Step 1:** Declare `@FunctionalInterface public interface StreamingRequestHandler extends RequestHandler {}`. Javadoc: opt-in by type, what is still validated, single read inside `handle()`, buffered accessors throw, decoded size cap surfaces from `read` as `BadRequestException` (413/400), uncoded bodies uncapped.

## Task 2: `Request` streaming support

**Files:** Modify `Request.java`; test in `RequestTest.java`

- [x] **Step 1: Write failing tests**
  - `streamingRequestExposesOnlyItsStream` — `bodyStream()` is the same instance; `bytes()`, `parsed()`, `asPojo()` throw `IllegalStateException` mentioning `bodyStream()`.
  - `streamingRequestKeepsItsStreamWhenPrincipalsAreAdded` — `withPrincipals` preserves stream and `isStreaming()`.
  - `streamingRequestRejectsANullStream` — NPE.
  - `bufferedRequestStreamsItsBytesAfresh` — two `bodyStream()` calls each return the full bytes.
  - `bufferedRequestWithoutABodyStreamsNothing` — `null` body streams empty.
- [x] **Step 2: Implement**
  - Add `private final InputStream stream;` — `null` for buffered requests; public constructors set it to `null`.
  - Turn the package-private 10-arg constructor into a `private` 11-arg one taking `InputStream stream` after `body`; `withPrincipals` passes `stream` through.
  - Add `public static Request streaming(InputStream body, String operationId, Map<String,String> pathParameters, String rawQuery, UnaryOperator<String> headerLookup, Map<String,Object> principals, HttpMethod method)`.
  - Add `bodyStream()`, `isStreaming()`, and a `requireBuffered()` guard called from `bytes()`, `parsed()`, `asPojo()`.
- [x] **Step 3:** `mvn test -Dtest=RequestTest` — green.

## Task 3: Lazy, capped decoder

**Files:** Create `internal/DecodingInputStream.java`; test in `DecodingInputStreamTest.java`

- [x] **Step 1: Write failing tests** — decodes as read; single-byte reads; empty body reads `-1` without opening the decoder; zero-length read touches nothing; exactly-at-cap allowed; over-cap → 413; malformed → 400 "malformed gzip"; truncated → 400; `close()` closes the raw stream before and after reading.
- [x] **Step 2: Implement**
  - Wrap the raw stream in a `PushbackInputStream(raw, 1)`.
  - `open()`: on first read, peek a byte; `-1` → mark empty; otherwise unread and call `coding.decode(raw)`.
  - `read(byte[], int, int)`: catch `IOException` from open/read → `BadRequestException(400, "malformed <token> request body")`; count decoded bytes; over cap → `BadRequestException(413, "decompressed request body exceeds N bytes")`.
  - `read()` delegates to the array form; `close()` closes the decoder if opened, else the raw stream.
- [x] **Step 3:** `mvn test -Dtest=DecodingInputStreamTest` — green.

## Task 4: `RequestBodyReader.stream`

**Files:** Modify `internal/RequestBodyReader.java`; test in `RequestBodyReaderTest.java`

- [x] **Step 1: Write failing tests** — identity returns the exchange stream as-is with headers unchanged; gzip returns a decoded stream and hides `Content-Encoding` / `Content-Length`; decoded stream is capped (413); unsupported coding → 415 before streaming.
- [x] **Step 2: Implement**
  - `public Streamed stream(HttpExchange)` reusing the `ContentEncodingHeader.parse` switch: `Identity` → raw stream; `Coded` → `new DecodingInputStream(coding, raw, maxDecompressedBytes)`; `Unsupported` → 415.
  - Extract `decodedHeaders(Headers, String decodedLength)` from `decoded(...)`; a `null` length hides `Content-Length`.
  - Add `public record Streamed(InputStream stream, UnaryOperator<String> headerLookup)`.
  - Leave `read(...)` unchanged.
- [x] **Step 3:** `mvn test -Dtest=RequestBodyReaderTest` — green.

## Task 5: Streaming branch in `RequestPreparationFilter`

**Files:** Modify `internal/RequestPreparationFilter.java`

- [x] **Step 1:** Add a 9-arg constructor taking `Set<String> streamingOperations`; the existing 8-arg constructor delegates with `Set.of()`.
- [x] **Step 2:** In `buildRequest`, route first; if the match's `operationId` is streaming, return `buildStreamingRequest(...)`. Otherwise read the body and continue exactly as before (404/405 still after the body read).
- [x] **Step 3:** `buildStreamingRequest`: `bodyReader.stream` (415) → `validateParameters` → peek one byte through a `PushbackInputStream`: empty + `required` → `required` 400; present → media type must be a key of `requestBody.content`, else `content-type` 400. No `TypeMapper` lookup, no parsing. Build with `Request.streaming(...)`.
- [x] **Step 4:** Extract `requiredBodyMissing()` and `unsupportedContentType(...)` and use them on the buffered path too.

## Task 6: Wiring and boot validation

**Files:** Modify `internal/SpecBinding.java`, `OpenApiServer.java`, `internal/ExtrasRouter.java`

- [x] **Step 1:** `SpecBinding.streamingOperations()` — `operationId`s whose handler is a `StreamingRequestHandler`.
- [x] **Step 2:** `OpenApiServer.wireBinding` passes `binding.streamingOperations()` to the filter.
- [x] **Step 3:** `Builder.validateBindings` calls `validateStreamingWiring`: a streaming handler on an operation without `requestBody` → `IllegalStateException` naming the operations.
- [x] **Step 4:** `ExtrasRouter.handle` — `hit instanceof StreamingRequestHandler` → `Request.streaming(...)` from `bodyReader.stream`, else the existing buffered request.
- [x] **Step 5:** Javadoc on `maxDecompressedRequestBytes` and `extraRoute`.

## Task 7: Integration tests

**Files:** Create `src/test/resources/streaming-openapi.json`, `StreamingRequestBodyIT.java`

- [x] **Step 1: Fixture spec** — `upload` (required `Upload-Id` header, required `application/octet-stream` body), `optionalUpload` (optional body), `secureUpload` (`apiKeyAuth`), `ping` (no body).
- [x] **Step 2: Tests**
  - 8 MiB identity body passes a 1 KiB `maxDecompressedRequestBytes`.
  - Handler sees `isStreaming()`, header access, and throwing buffered accessors.
  - gzip decoded as read; no `Content-Encoding` / `Content-Length` visible.
  - gzip over cap → 413; malformed gzip → 400; unknown coding → 415.
  - Missing required header → 400, handler not invoked.
  - Undeclared `Content-Type` → 400 (use `setHeader`; `header` appends a second value).
  - Empty body on required → 400; empty body on optional → handler gets an empty stream.
  - Unauthenticated → 401 before handler; authenticated keeps its stream.
  - Handler that leaves a 16 KiB body unread still responds.
  - Interceptors see `isStreaming()`.
  - Extra route streams its body.
  - Streaming handler on `ping` fails `build()`.
- [x] **Step 3:** `mvn verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=StreamingRequestBodyIT` — green.

## Task 8: README

- [x] **Step 1:** New "Streaming request bodies" section after "Request body content types": example, what still runs, what changes (no schema validation, buffered accessors throw, single read inside `handle()`, 64 KiB drain, decoded cap from `read`, uncoded bodies uncapped), `Request.streaming(...)` for tests.
- [x] **Step 2:** TOC entry, Highlights bullet, note under "Content encoding", `ScopedValue` caveat.

## Task 9: Content-Type matching

**Files:** Create `internal/RequestContentType.java`, `RequestContentTypeTest.java`; modify `internal/RequestPreparationFilter.java`; extend the fixture spec and IT.

- [x] **Step 1: Write failing tests** — exact beats `type/*` beats `*/*`; parameters and case ignored; undeclared type → empty; missing header → `application/json` when declared, else `application/octet-stream` (matches `application/octet-stream` and `*/*`, not `text/plain`).
- [x] **Step 2: Implement** `RequestContentType.match(header, content)` returning `Match(mediaType, declared, content)`, and `mediaType(header, content)` for error messages.
- [x] **Step 3:** Use it in `validateAndParseBody` (schema from the match; mapper by request media type, then by the declared key) and in `buildStreamingRequest`.
- [x] **Step 4: IT** — fixture ops `anyUpload` (`*/*`, streaming) and `textRange` (`text/*`, buffered); tests: upload without `Content-Type` → 200; `image/png` on `*/*` → 200; `text/plain; charset=utf-8` on `text/*` parses → 200; `application/json` on `text/*` → 400.

## Task 10: Listing headers

**Files:** Modify `Request.java`, `internal/RequestBodyReader.java`, `internal/RequestPreparationFilter.java`, `internal/ExtrasRouter.java`; tests in `RequestTest`, `RequestBodyReaderTest`, IT.

- [x] **Step 1: Write failing tests** — lookup-only `Request` lists nothing; `withHeaders` lists case-insensitively, is unmodifiable, copies its input; headers, stream and after-hook queue survive `withPrincipals`; reader lists headers with the decoded view; IT: multi-value header listed, decoded body lists no `Content-Encoding` / `Content-Length`.
- [x] **Step 2:** `RequestBodyReader.Body` / `Streamed` carry `Map<String, List<String>> headers` (case-insensitive `TreeMap` copy) with a derived `headerLookup()`; `decodedHeaders` drops `Content-Encoding` and replaces or drops `Content-Length`.
- [x] **Step 3:** `Request` gains a `headers` field (private constructor), `headers()` and `withHeaders(Map)` deriving the lookup from the map; adapters call `.withHeaders(body.headers())`.

## Task 11: Raw bodies

**Files:** Modify `StreamingRequestHandler.java`, `internal/RequestBodyReader.java`, `internal/SpecBinding.java`, `internal/RequestPreparationFilter.java`, `internal/ExtrasRouter.java`; tests in `StreamingRequestHandlerTest`, `RequestBodyReaderTest`, IT.

- [x] **Step 1: Write failing tests** — `decodeContent()` defaults to `true`; `raw(...)` returns `false`, delegates, rejects `null`; `stream(exchange, false)` returns the exchange stream with headers unchanged and doesn't reject `br`; IT: 64 KiB of gzip past a 1 KiB cap arrives byte-identical with `Content-Encoding: gzip`; `br` → 200.
- [x] **Step 2:** `default boolean decodeContent()` and `static StreamingRequestHandler raw(StreamingRequestHandler)`.
- [x] **Step 3:** `RequestBodyReader.stream(exchange, boolean decode)`; `stream(exchange)` delegates with `true`.
- [x] **Step 4:** `SpecBinding.streamingHandlers()` (map, replacing `streamingOperations()`); the filter and extras router pass `handler.decodeContent()`.

## Task 12: Concurrency limit

**Files:** Create `internal/StreamingLimit.java`, `StreamingLimitTest.java`; modify `OpenApiServer.java`, `internal/RequestPreparationFilter.java`, `internal/ExtrasRouter.java`; tests in `OpenApiServerBuilderTest`, IT.

- [x] **Step 1: Write failing tests** — `UNLIMITED` always admits; cap admits N, then again after `release`; rejection is 503 `application/problem+json` with `Retry-After` rounded up (1500 ms → `2`, 0 → `0`); invalid arguments → IAE / NPE on the builder; IT: with a cap of 1 and a handler held on a latch, a second upload → 503 `Retry-After: 3` (2.5 s) without invoking the handler, and a later one → 200; validation and security rejections return their slot; buffered requests still served while the slot is held; a streaming extra route shares the cap.
- [x] **Step 2:** `StreamingLimit.of(max, retryAfter)` over a `Semaphore`; `tryAcquire`, `release`, `rejection()`.
- [x] **Step 3:** `Builder.maxConcurrentStreamingRequests(int)` (1 s) and `(int, Duration)`; `HandlerConfig.streamingLimit`; passed to every `RequestPreparationFilter` and the `ExtrasRouter`.
- [x] **Step 4:** Filter: `tryAcquire` right after routing a streaming operation, else throw a private stackless `OverStreamingLimit` that `doFilter` renders as `streamingLimit.rejection()`; release when building fails, and in the inner chain's `finally` before after-response hooks. Extras: same around handle and render.
- [x] **Step 5:** README "Raw bodies", "Limiting concurrent streaming requests", "Matching the declared media types", `headers()` note, Highlights; spec "Additions".

## Task 13: Review fixes

Two independent reviews (Opus, Fable) returned SHIP WITH FIXES.

- [x] **Step 1: Slot taken too early (blocker, reproduced by the Opus review).** The slot was taken right after routing, before the one-byte peek and security, so N clients sending headers only — authenticated or not — held every slot indefinitely. Move `tryAcquire` / `release` from `RequestPreparationFilter` to `DispatchHandler` (5-arg constructor; 4-arg delegates with `UNLIMITED`), around interceptors and the handler, with the release in a `finally`. This also closes the `Error` leak both reviews found in the filter's `catch (RuntimeException | IOException)`. Store the 503 as `RESPONSE_ATTR` so after-response hooks see shed requests.
- [x] **Step 2: Transport failures reported as malformed bodies.** `DecodingInputStream` wraps the raw stream in a `Source` that tags its `IOException`s; `read` rethrows a tagged failure unchanged and turns only the coding's own failures into a 400.
- [x] **Step 3: Tests.** IT: a headers-only stalled client (with and without credentials) holds no slot; a throwing handler, a 413 mid-read and a throwing streaming extra each give their slot back; after-response hooks see the 503. Unit: connection failure before the first byte and mid-body propagate as the same `IOException`.
- [x] **Step 4: Docs.** README order of checks (cap after security), "one decoded byte", 2 GiB decoded ceiling, disconnects, hooks counting shed requests, `Expect: 100-continue`; javadoc on the builder, `StreamingLimit`, `Request.streaming`; spec constraints, including the pre-existing 404/405-after-read on the buffered path, left for a separate change.

## Task 14: Final verify and commit

- [x] **Step 1:** `pre-commit run --files <changed files>`.
- [x] **Step 2:** `mvn verify` — unit and integration tests green.
- [x] **Step 3:** SonarLint sweep (`sonar_analyze_file`) over the changed main and test files; fix new findings. The `java:S9391` hit in `OpenApiServer.validateHandlerWiring` predates this change.
- [x] **Step 4:** Note `StreamingRequestHandler` in `CLAUDE.md`'s architecture section.
- [x] **Step 5:** Commit on `feat/streaming-request-bodies`: `feat: Support streaming request bodies` (signed).

## Task 15: Streaming decompression cap

Spec: "Streaming decompression cap". Lifts the 2 GiB ceiling on decoded streams without touching the buffered path.

- [x] **Step 1:** `RequestBodyReader`: three-argument constructor `(maxDecompressedBytes, maxStreamedDecompressedBytes, decoders)`, both validated positive; the two-argument constructor delegates with the same cap. `stream` passes the streamed cap to `DecodingInputStream`; `read` is unchanged.
- [x] **Step 2:** `Builder.maxDecompressedStreamingRequestBytes(long)` — any positive `long`; unset falls back to `maxDecompressedRequestBytes` at `build()`. Javadoc on both setters.
- [x] **Step 3:** Tests. Unit: streamed body held to its own cap, buffered body keeps its cap when the streamed one is higher, constructor rejects a non-positive streamed cap; builder accepts `Integer.MAX_VALUE + 1` and `Long.MAX_VALUE`, rejects 0 and -1. IT: a gzip body past the buffered cap streams when the streaming cap is higher; one past the streaming cap is 413.
- [x] **Step 4:** README "Raising the cap for streamed bodies", cross-links from "Content encoding", the streaming bullets and "Raw bodies"; spec section and constraint; this task.
- [x] **Step 5:** `pre-commit run`, `mvn verify`, SonarLint on changed files; commit `feat: Separate decompression cap for streamed bodies` (signed) and push to PR #131.
