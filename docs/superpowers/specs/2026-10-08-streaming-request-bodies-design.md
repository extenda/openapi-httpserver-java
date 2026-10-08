# Streaming Request Bodies

**Status:** Proposed
**Date:** 2026-10-08
**Author:** thced

## Goal

Let a handler read the request body as it arrives, so memory per request doesn't grow with the
size of the body. Today `RequestBodyReader` calls `readAllBytes()` before any filter, interceptor
or handler runs, and `Request` only exposes the buffered body. A consumer that must accept
uploads of arbitrary size has no option but to leave the library and serve that route from a
plain `HttpServer`, giving up parameter validation, security, interceptors and error rendering
along with the buffering.

## API

### Opt-in: the handler's type

```java
@FunctionalInterface
public interface StreamingRequestHandler extends RequestHandler {}
```

A handler registered by `operationId` (or as an extra route) that implements
`StreamingRequestHandler` gets a streamed body; every other handler is unchanged.

Alternatives considered:

- **A spec extension** (`x-...` on the operation). Visible in the published contract, but extra
  routes have no spec, a consumer who doesn't own the spec can't opt in, and the buffering is
  an implementation choice rather than part of the API contract.
- **A builder method keyed by `operationId`.** `operationId`s are only unique per spec under
  `addSpec`, so it would need a spec qualifier.

The handler type has none of these problems and keeps the decision next to the code that reads
the stream.

### Request

- `InputStream bodyStream()` — the live stream for a streaming request; a fresh stream over
  `bytes()` otherwise, so helpers can be written once.
- `boolean isStreaming()` — lets interceptors and hooks avoid the buffered accessors.
- `bytes()`, `parsed()`, `asPojo()` throw `IllegalStateException` on a streaming request.
- `static Request streaming(InputStream, operationId, pathParameters, rawQuery, headerLookup, principals, method)`
  — the existing constructors are untouched. `withPrincipals` carries the stream through.

## Behaviour

`RequestPreparationFilter` routes before reading the body. For a streaming operation it:

- opens the body through `RequestBodyReader.stream` (415 for an unregistered coding, as today);
- validates path, query and header parameters;
- peeks one byte: an empty body fails a required request body with 400; a present body must
  carry a `Content-Type` declared under `requestBody.content` — the same decisions, in the same
  order, the buffered path makes from the body's length. No `TypeMapper` is required;
- skips parsing and schema validation.

Buffered operations keep their exact order (body read, then 404/405, then validation).

`SecurityFilter`, interceptors, decorators and after-response hooks run unchanged.

### Content coding

A coded body is decoded lazily by `DecodingInputStream`, which opens the decoder once the first
byte arrives (so an empty coded body reads as empty, as when buffered) and counts decoded bytes
against `maxDecompressedRequestBytes`. Exceeding the cap throws `BadRequestException(413)` from
`read`; a decoder failure throws `BadRequestException(400)`. Both propagate out of the handler to
the `ExceptionHandler`. The header view hides `Content-Encoding` and `Content-Length` for a decoded
body, since the decoded length is unknown up front. Identity bodies are passed through uncapped,
matching the buffered path.

### Boot validation

A `StreamingRequestHandler` registered for an operation that declares no `requestBody` fails
`build()` with `IllegalStateException`.

## Additions

Four gaps surfaced when checking the feature against a real consumer: an ingest endpoint whose
contract allows a 400 only for a missing or empty required header or an empty body, stores the
request headers alongside the body, has no size cap, and sheds load with 503. None of the four
breaks existing API or behaviour; the first only accepts requests that were answered 400 before.

### Content-Type matching

`RequestContentType` matches the request's media type against `requestBody.content`, for both the
buffered and the streaming path:

- comparison ignores case;
- the most specific declaration wins: exact, then `type/*`, then `*/*`;
- a missing `Content-Type` is `application/json` when the operation declares it (unchanged), and
  `application/octet-stream` otherwise, as RFC 9110 §8.3 allows.

The matched key selects the schema. On the buffered path, the `TypeMapper` is looked up by the
request's own media type first and by the matched key second.

Rejected alternative: treating a missing `Content-Type` as `application/octet-stream`
everywhere. It would break JSON clients that omit the header.

### Listing headers

`Request.headers()` returns every header with all its values, case-insensitive and unmodifiable.
`Request.withHeaders(Map)` returns a copy whose `headers()` and `header(name)` both read the map;
the server builds every request this way. A `Request` built from a lookup function alone lists no
headers, so the existing constructors keep their signatures. `RequestBodyReader.Body` and
`Streamed` carry the header map instead of a lookup function, with the same decoded view:
`Content-Encoding` removed, `Content-Length` the decoded length or absent.

### Raw bodies

`StreamingRequestHandler.decodeContent()` defaults to `true`. Returning `false`, or wrapping a
lambda with `StreamingRequestHandler.raw(handler)`, gives the handler the body exactly as sent:
coded, uncapped, with `Content-Encoding` and `Content-Length` visible and no 415 for an unknown
coding. `RequestBodyReader.stream(exchange, decode)` implements both modes.

### Concurrency limit

`Builder.maxConcurrentStreamingRequests(int)` and `(int, Duration retryAfter)` cap requests to
streaming handlers server-wide, through one `StreamingLimit` (a `Semaphore`) shared by every
binding and the extras router.

- The slot is taken in `DispatchHandler`, after validation, the one-byte peek and security, with
  `tryAcquire` — no queueing — and released in a `finally` once the response is rendered. A
  client that stalls before sending its body, or isn't authenticated, therefore never holds one.
  (A first version took the slot right after routing; review showed N clients sending headers
  only could hold every slot indefinitely, since the JDK server has no request read timeout.)
- Over the cap, the dispatcher writes a `503` `application/problem+json` with `Retry-After`
  (whole seconds, rounded up; 1 by default) itself, and stores it as the exchange's response, so
  after-response hooks see it — which is how a consumer counts shed requests. Interceptors, the
  handler and the `ExceptionHandler` don't run.
- Extras take the slot just before the handler, since they have no validation or security, and
  release it after rendering.
- Buffered requests are not counted. A server-wide connection cap in the style of Tomcat's
  `maxConnections` would also shed health probes and cheap API calls under an upload burst, which
  is the opposite of what shedding is for.

## Constraints

- The stream must be read inside `handle()`, on the request thread. After the handler returns,
  the response is rendered and the exchange closed; the JDK drains at most
  `sun.net.httpserver.drainAmount` (64 KiB) of an unread body before closing the connection.
- A 413 or 400 from decoding can arrive after the handler has forwarded earlier bytes. Handlers
  writing to storage should commit only after the final read.
- Uncoded bodies have no size limit, streamed or not. A request size limit is a separate
  concern.
- Interceptors never see shed requests; after-response hooks do, with the 503 and its
  `Retry-After`.
- `Expect: 100-continue` is answered by the JDK server before any filter runs, so a shed client
  may still send its body; the server drains up to 64 KiB of it, then closes the connection.
- The decoded-size cap can't exceed 2 GiB (`maxDecompressedRequestBytes` is bounded by the
  buffered path's `byte[]`); raw bodies have no cap.
- `DecodingInputStream` turns only the coding's failures into a 400. A connection failure is
  tagged as it leaves the raw stream and rethrown unchanged, so a client disconnect is an
  `IOException`, as for an uncoded body.
- On the buffered path, a 404 or 405 is still answered after the body is read in full, as on
  `master`; moving it ahead of the read would change which error a doubly-wrong request gets, so
  it is left for a separate change.

## Testing

- `DecodingInputStreamTest` — lazy open, empty body, cap boundary, malformed and truncated input,
  close semantics.
- `RequestBodyReaderTest` — identity pass-through, decoded stream and header view, cap, 415.
- `RequestTest` — streaming accessors, `withPrincipals`, buffered `bodyStream()`.
- `StreamingRequestBodyIT` — 8 MiB identity body past a 1 KiB decompression cap, gzip decode,
  413/400/415, parameter / content-type / required-body validation, security rejection before the
  handler, unread body still answered, interceptors, extra routes, boot validation; missing
  `Content-Type`, `*/*` and `text/*` matching on both paths; header listing; raw bodies; the
  concurrency limit (503 with `Retry-After`, slot returned on rejection, buffered requests and
  extras).
- `RequestContentTypeTest`, `StreamingLimitTest`, `StreamingRequestHandlerTest`, and additions to
  `RequestTest`, `RequestBodyReaderTest` and `OpenApiServerBuilderTest`.
