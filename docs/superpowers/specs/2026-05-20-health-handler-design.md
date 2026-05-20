# Health handler

**Date:** 2026-05-20
**Status:** Design — ready for implementation plan

## Problem

Services built on this library need a `/health` endpoint that reports
overall health plus per-dependency status. The expected wire format is:

```json
{
  "outcome": "Up",
  "dependencies": [
    { "id": "jdbc", "status": "Up" }
  ]
}
```

We want a ready-to-use `HttpHandler` in this repo that produces that exact
shape. The handler must not depend on any specific health-check provider —
callers supply the data through a `Supplier`, so they can plug in whatever
mechanism (off-the-shelf or in-house) computes their dependency statuses.

## Goals

1. Add `Handlers.healthHandler(Supplier<HealthOutcome>)` that:
    - Accepts GET and HEAD only (405 otherwise, with `Allow: GET, HEAD`).
    - Returns `200 OK` with `Content-Type: application/json` when `outcome` is `Up`.
    - Returns `503 Service Unavailable` with the same body shape when `outcome` is `Down`.
    - Never propagates a probe failure as a 500 — a throwing `Supplier` yields
      `Down` + empty dependency list + 503.
2. Define small public records `HealthOutcome` and `Dependency` in
    `com.retailsvc.http` that own the wire shape, so this library has no
    runtime or compile-time dependency on any specific health-check provider.
3. Reuse existing infrastructure (`MethodLimitedHandler`, hand-rolled
    JSON rendering á la `ProblemDetailRenderer`). No new third-party deps.

## Non-goals

- Bundling or running health checks. Callers compute their own outcome
  (typically by adapting whatever check-runner they use into a
  `HealthOutcome`) and pass it in via the `Supplier`.
- Caching of probe results. If a caller's checks are expensive, they
  memoize on their side of the `Supplier`.
- A configurable wire format — the field names `outcome`, `dependencies`,
  `id`, `status` and the string values `Up` / `Down` are fixed.
- Configurable Content-Type or status codes — fixed at `application/json` +
  200/503.
- An integration test — unit coverage is sufficient; `MethodLimitedHandler`
  itself is already integration-tested elsewhere.

## Design

### Public types — `com.retailsvc.http`

```java
public record HealthOutcome(String outcome, List<Dependency> dependencies) {
  public HealthOutcome {
    Objects.requireNonNull(outcome, "outcome");
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }

  public boolean isUp() {
    return "Up".equalsIgnoreCase(outcome);
  }
}

public record Dependency(String id, String status) {
  public Dependency {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }
}
```

`HealthOutcome.isUp()` is case-insensitive so callers that pass `"Up"`,
`"UP"`, or `"up"` all map to a healthy 200 response.

### Public API — `Handlers.healthHandler`

```java
public static HttpHandler healthHandler(Supplier<HealthOutcome> probe) {
  Objects.requireNonNull(probe, "probe");
  return new MethodLimitedHandler(exchange -> {
    try (exchange) {
      HealthOutcome outcome;
      try {
        outcome = probe.get();
      } catch (RuntimeException e) {
        LOG.warn("Health probe threw", e);
        outcome = new HealthOutcome("Down", List.of());
      }
      byte[] body = HealthRenderer.toJson(outcome).getBytes(UTF_8);
      int status = outcome.isUp() ? HTTP_OK : HTTP_UNAVAILABLE;
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  });
}
```

`HTTP_OK` and `HTTP_UNAVAILABLE` come from `java.net.HttpURLConnection` (per
project convention — no magic numbers).

### Internal — `com.retailsvc.http.internal.HealthRenderer`

Package-private final class with a private constructor and a single
`static String toJson(HealthOutcome)` method. Implementation mirrors
`ProblemDetailRenderer`: hand-rolled `StringBuilder`, manual JSON-string
escaping for `\\`, `\"`, `\n`, `\r`, `\t`, `\b`, `\f`, and `\uXXXX` for any
remaining control characters below `0x20`.

### Caller-side wiring (illustrative, not part of this repo)

```java
server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(mapper)
    .handlers(operationHandlers)
    .addHandler("/health", Handlers.healthHandler(() -> {
      // Caller computes per-dependency statuses however they choose
      // and adapts the result into HealthOutcome / Dependency.
      List<Dependency> deps = List.of(
          new Dependency("jdbc", checkDatabase() ? "Up" : "Down"),
          new Dependency("cache", checkCache() ? "Up" : "Down"));
      String outcome = deps.stream().allMatch(d -> "Up".equalsIgnoreCase(d.status()))
          ? "Up" : "Down";
      return new HealthOutcome(outcome, deps);
    }))
    .build();
```

The `Supplier` is the only place that knows how to compute health, which is
exactly where any third-party integration belongs.

## Error handling

- `Supplier` returns `null`: `Objects.requireNonNull` inside the handler
  produces an NPE; this falls outside the `RuntimeException` catch and
  propagates up through `ExceptionFilter` (yielding a 500). Probes are
  expected to return a value; treating a `null` return as a programming
  error is intentional.
- `Supplier` throws `RuntimeException`: caught; logged at `warn`; rendered
  as `Down` with empty dependency list and 503.
- IOException while writing the response: not caught here; `ExceptionFilter`
  handles it.

## Testing

Unit tests only (Surefire). New file `HealthHandlerTest` (or extension of
`HandlersTest`):

- GET, `Up` outcome with dependencies → 200, `application/json`, body
  equals expected JSON (parsed back via Jackson in test scope, asserted
  field by field).
- GET, `Up` outcome with empty dependency list → 200, body has empty
  array.
- GET, `Down` outcome → 503, body still rendered.
- HEAD → status code only, no body bytes.
- POST → 405 with `Allow: GET, HEAD` header.
- Probe throws `RuntimeException` → 503, body `{"outcome":"Down","dependencies":[]}`.
- Probe returns `null` → propagates (assertion: 500 via the default
  exception handler).

New file `HealthRendererTest`:

- Round-trip outcomes through Jackson to confirm valid JSON.
- Strings containing `"`, `\`, newline, tab, control char `` are
  escaped correctly.

Records `HealthOutcome` and `Dependency` get tiny tests for null/empty
argument validation and (`HealthOutcome` only) `isUp()` case-insensitivity.

## Out of scope / future

- Wiring the handler into `ServerLauncher` (the example launcher) — not
  needed; the launcher exists for local development of the OpenAPI flow.
- A second `healthHandler` overload that takes a `Callable` or
  `CompletionStage` — no concrete need yet.
- An integration test that exercises the handler through `OpenApiServer`
  end-to-end.
