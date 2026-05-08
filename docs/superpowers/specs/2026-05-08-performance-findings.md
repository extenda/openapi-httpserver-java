# Performance Findings — JFR @ 2026-05-08

**Date:** 2026-05-08
**Status:** Documented for follow-up; not yet implemented
**Source:** Java Flight Recorder run during a 45 s / 30 VU k6 load test against `ServerLauncher` on the post-refactor branch (`refactor/openapi-3.1-readiness`, commit ~`bbb3c07`). 1,449,132 HTTP requests, ~32k rps, 0% HTTP failures, 100% k6 checks passing.

> The JFR file itself is not committed; this document captures the analysis. Re-run with `-XX:StartFlightRecording=…` against `com.retailsvc.http.start.ServerLauncher` to reproduce.

## TL;DR

The library is functionally correct under load, but several easy wins exist for both throughput and GC pressure. Three changes — caching `Spec.basePath`, caching compiled `Pattern` for string validation, and memoising ref resolution — together remove ~470 MB of unnecessary allocation per million requests and visible CPU samples in `Spec.basePath` / `Pattern.compile`. Two further small wins clean up parameter validation. One large item (streaming body parse) is a possible future direction but is a public-API change and is parked.

## Recording profile

- 33,798 `jdk.ExecutionSample` events (sampling profiler)
- 12,834 `jdk.ObjectAllocationSample` events
- ~164 s wall-clock; load applied for the middle 45 s
- Most CPU samples (21,278) are `Unsafe.park` — virtual threads parked on I/O, healthy.

## Hot frames in our code (CPU samples)

| Samples | Location | Notes |
|---:|---|---|
| 77 | `RequestPreparationFilter.doFilter` | Per-request orchestration; the bulk is `readAllBytes` |
| 44 | `start.PostDataHandler.handle` | Test handler echo |
| 39 | `start.ParamHandler.handle` | Test handler |
| 33 | `start.GetDataHandler.handle` | Test handler |
| 31 | `validate.DefaultValidator.validateString` | **Pattern recompilation** — see W2 |
| 26 | `spec.Spec.basePath` | **URI recreation per request** — see W1 |
| 18 | `start.PostListObjectsHandler.handle` | Test handler |
| 16 | `RequestPreparationFilter.validateParameters` | Pointer-string + query-map assembly — see W4/W5 |
| 14 | `spec.PathTemplate.match` | Already a precompiled regex; nothing to do |
| 12 | `Request.operationId` | Inside JDK `ScopedValue.get()` traversal |

## Allocation hot-spots in our code (sampled)

The numbers below are extrapolated from JFR's allocation sample weights to total estimated bytes over the 45 s of load. Rank order is what matters; absolute numbers should be read as "MB-scale" indicators.

| Site | Est. alloc | Cause | Action |
|---|---:|---|---|
| `RequestPreparationFilter.doFilter` → `byte[]` | ~24 GB | `getRequestBody().readAllBytes()` — fresh buffer per request | Defer (see W6) |
| `Request.operationId` → `Object[]` | ~237 MB | JDK-internal `ScopedValue.get()` carrier traversal | Mitigate by reading the context once per handler (see W7) |
| `validate.DefaultValidator.validateString` → `int[]` + `Matcher` | ~215 MB | `Pattern.compile(s.pattern())` per request | **W2** |
| `RequestPreparationFilter.validateParameters` → `String` + `HashMap` | ~180 MB | Pointer-string concat + per-request query parse | **W4 + W5** |
| `start.*Handler` → `LoggingEvent` (logback) | ~190 MB | `LOG.debug(...)` allocates even when level disabled in some logback paths | Cosmetic; only test handlers |
| `Spec.stripPrefix` → `String` + `byte[]` | ~150 MB | `ref.substring(prefix.length())` per `$ref` resolve | **W3** |
| `ScopedValue.Carrier` | ~117 MB | One per request; inherent to `ScopedValue.where(...)` | Don't address |
| `Spec.basePath` → `URI` | ~105 MB | `URI.create(servers[0].url())` per request | **W1** |

## Recommended changes

Numbered W1–W7 in priority order (easy + high-impact first).

### W1 — Cache `Spec.basePath` once at construction

**Files:** `src/main/java/com/retailsvc/http/spec/Spec.java`

`basePath()` currently does:
```java
public String basePath() {
  if (servers.isEmpty()) {
    throw new IllegalStateException("no servers declared");
  }
  return Optional.ofNullable(URI.create(servers.get(0).url()).getPath()).orElse("");
}
```

Called from `RequestPreparationFilter.stripBasePath` on **every** request. The result is spec-static — compute once.

**Sketch:**
- Add a `private final String basePath;` field on `Spec`.
- Compute it in the canonical constructor (or in `Spec.from` factory) — same logic, run once.
- Replace the method body with `return basePath;`.

**Impact:** ~100 MB of URI allocs gone; ~26 CPU samples eliminated; small but consistent latency win on every request.

### W2 — Cache compiled `Pattern` for string validation

**Files:** `src/main/java/com/retailsvc/http/validate/DefaultValidator.java`

Currently:
```java
if (s.pattern() != null && !Pattern.compile(s.pattern()).matcher(str).matches()) {
  fail(pointer, "pattern", "does not match pattern " + s.pattern(), str);
}
```

`Pattern.compile` is recompiled on every validation. Patterns are immutable and thread-safe.

**Sketch:**
- Option A (smaller): hold a `ConcurrentHashMap<String, Pattern>` field on `DefaultValidator`; `getOrDefault`/`computeIfAbsent` to memoise. Cache is unbounded but pattern strings are spec-static so it's bounded by the spec.
- Option B (cleaner): compile the `Pattern` at `SchemaParser.parse(...)` time and store it on `StringSchema` as an extra component. Requires changing `StringSchema` to carry a `Pattern` (or a record containing both raw + compiled). Slightly bigger surface change, but eliminates the cache lookup too.

Recommendation: **A first**, B as a refinement if profiling shows the lookup itself is hot.

**Impact:** ~215 MB of `int[]`/`Matcher` allocations gone; the 31 `validateString` CPU samples drop substantially.

### W3 — Memoise `Spec.resolveSchema` / `Spec.resolveParameter`

**Files:** `src/main/java/com/retailsvc/http/spec/Spec.java`

`stripPrefix(...)` does `ref.substring(prefix.length())` plus a map lookup on every ref resolution. Refs are spec-static; once resolved, they don't change.

**Sketch:**
- Two `ConcurrentHashMap` fields on `Spec`: `resolvedSchemas` and `resolvedParameters`.
- `computeIfAbsent` on lookup. Cache size is bounded by the spec's component count.

**Impact:** ~150 MB of allocs gone; tightens the validator hot path when schemas use `$ref`.

### W4 — Skip `parseQuery` when the operation has no QUERY parameters

**Files:** `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`

`validateParameters` currently calls `parseQuery(...)` unconditionally, allocating a HashMap even for routes that take no query params (most of them in the smoke test).

**Sketch:**
```java
private void validateParameters(HttpExchange exchange, Operation op, Map<String, String> pathParams) {
  Map<String, String> query = null;     // build only on demand
  for (Parameter p : op.parameters()) {
    String value = switch (p.in()) {
      case PATH -> pathParams.get(p.name());
      case QUERY -> {
        if (query == null) query = parseQuery(exchange.getRequestURI().getQuery());
        yield query.get(p.name());
      }
      case HEADER -> exchange.getRequestHeaders().getFirst(p.name());
      case COOKIE -> null;
    };
    …
  }
}
```

**Impact:** Eliminates a HashMap (and the `String.split("&")` work) on every GET that has no query parameters.

### W5 — Precompute `Parameter` JSON-pointer at parse time

**Files:** `src/main/java/com/retailsvc/http/spec/Parameter.java`, `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java`

The pointer string `"/" + p.in().name().toLowerCase(Locale.ROOT) + "/" + p.name()` is rebuilt per request per parameter. It's spec-static.

**Sketch:**
- Add a `private final String pointer` (or computed accessor) on `Parameter`. Possibly via a `pointer()` method that lazily memoises, since `Parameter` is currently a plain record. A non-record helper on the validate side would also work.
- Replace the runtime `"/" + … + p.name()` with `p.pointer()`.

**Impact:** Eliminates a few `StringBuilder` + `String` allocs per parameter per request. Small per-request, but parameters validate on every request.

### W6 — (Future / public API) Stream-validate the request body

**Files:** `src/main/java/com/retailsvc/http/JsonMapper.java`, `RequestPreparationFilter.java`

`getRequestBody().readAllBytes()` allocates ~24 GB worth of throwaway `byte[]` over a million requests at moderate body sizes. Eliminating that means reading-and-validating in a single pass — `JsonMapper.mapFrom(InputStream)` instead of `mapFrom(byte[])`, and forgoing storing the raw bytes for handlers that use `Request.bytes()`.

This is a **public API change** and changes handler ergonomics: handlers that need the raw bytes would have to opt in to caching. Defer until / unless we have a concrete throughput goal that needs it.

### W7 — Read context once per handler

**Files:** `src/main/java/com/retailsvc/http/Request.java` (consumer-facing)

`Request.bytes()`, `parsed()`, `operationId()`, `pathParams()` each do `CONTEXT.get()` independently. Every `get()` walks the JDK's scope chain and allocates a small `Object[]` (sampled at ~237 MB across the run). Internal callers (us) and well-written external handlers should hoist a single `RequestContext`:

**Optional new accessor:**
```java
public static RequestContext current() { return CONTEXT.get(); }
```

Add as an internal escape hatch (or a documented optimisation in handler code) without removing the four typed accessors. `DispatchHandler` should use `current().operationId()` to halve its `ScopedValue.get` cost.

**Impact:** Marginal but free; documents the off-thread-capture pattern users will need anyway.

## Out of scope for the perf PR

- `ScopedValue.Carrier` per-request allocation — JDK-API-mandated, not ours to fix.
- Test-handler logging allocs — those handlers are example code, not library code.
- `LinkedList`/`ReferencePipeline` use in `PostListObjectsHandler`/`ParamHandler` — same, test handlers.
- The k6 / load-shape itself — that's just the measurement harness.

## Sequencing and verification

Suggested PR shape: **W1 → W2 → W3 → W4 → W5** in five small commits, each verifiable independently with `mvn verify`. Add a `JfrAware` benchmark or rerun the k6 + JFR session before/after to confirm impact. W7 is a one-liner addition that can ride along.

W6 deserves its own design doc and probably its own PR after the immediate wins land — and only if a real throughput target makes it necessary.
