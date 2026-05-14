# openapi-httpserver-java

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=extenda_openapi-httpserver-java&metric=alert_status&token=c87f52089c6158081787f26e272d0a0e412c205b)](https://sonarcloud.io/dashboard?id=extenda_openapi-httpserver-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=extenda_openapi-httpserver-java&metric=coverage&token=c87f52089c6158081787f26e272d0a0e412c205b)](https://sonarcloud.io/dashboard?id=extenda_openapi-httpserver-java)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=extenda_openapi-httpserver-java&metric=code_smells&token=c87f52089c6158081787f26e272d0a0e412c205b)](https://sonarcloud.io/dashboard?id=extenda_openapi-httpserver-java)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=extenda_openapi-httpserver-java&metric=duplicated_lines_density&token=c87f52089c6158081787f26e272d0a0e412c205b)](https://sonarcloud.io/dashboard?id=extenda_openapi-httpserver-java)
[![WorkFlow](https://github.com/extenda/openapi-httpserver-java/actions/workflows/commit.yaml/badge.svg)](https://github.com/extenda/openapi-httpserver-java/actions)


# OpenAPI Server Library
A lightweight Java library for creating HTTP servers based on OpenAPI specifications.


## Overview
This library provides a simple way to create an HTTP server that implements OpenAPI specifications.

It is designed to be simple to use while providing the essential features needed for creating efficient HTTP servers in Java.

## Getting Started

### Prerequisites
- Java SDK 25 or later
- A serialization library, e.g. Gson or Jackson
- OpenAPI specification file in JSON format (`openapi.json`)


### Basic Usage
1. Create an OpenAPI specification file named `openapi.json` in your project resources.
2. Define your handlers using the `RequestHandler` functional interface:
``` java
// Inline lambda — returns JSON using the built-in Gson mapper.
RequestHandler getDataHandler = req ->
    req.respond(200).json(Map.of("id", "some-id"));

// Class form — reads raw bytes or the pre-parsed body object.
public class PostDataHandler implements RequestHandler {
  @Override
  public void handle(Request request) throws IOException {
    // Access the raw request body bytes.
    byte[] body = request.bytes();
    // Or get the already-parsed object (Map / List) produced by the registered TypeMapper.
    Object parsed = request.parsed();
    // Path parameters, query parameters, and headers are also available.
    String id = request.pathParams().get("id");
    String filter = request.queryParam("filter");
    String corr = request.header("correlation-id");

    request.respond(200).json(parsed);
  }
}
```

3. Initialize the server:
``` java
public class YourServerLauncher {
  public static void main(String[] args) throws Exception {
    Gson gson = new Gson();

    // Parse spec to a generic Map (works for JSON; for YAML use SnakeYAML).
    String text = Files.readString(Path.of("openapi.json"));
    Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
    Spec spec = Spec.from(raw);

    // Handlers by operationId.
    Map<String, RequestHandler> handlers = new HashMap<>();
    handlers.put("get-data", getDataHandler);
    handlers.put("post-data", new PostDataHandler());

    var server = OpenApiServer.builder()
        .spec(spec)
        .handlers(handlers)
        .exceptionHandler(Handlers.defaultExceptionHandler())
        .build();
  }
}
```

### YAML specifications
For YAML, replace the JSON parsing line with SnakeYAML:
``` java
Map<String, Object> raw = new Yaml().load(Files.newInputStream(Path.of("openapi.yaml")));
```
The rest is identical.

### JSON mapping

The library ships an internal `GsonJsonMapper` that is auto-registered for `application/json` when Gson is on the classpath and no user-supplied JSON mapper has been registered. It:

- Returns JSON integers as `Long` and fractional numbers as `Double`.
- Writes JSR-310 types (`Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, `LocalDate`, `LocalTime`) as ISO-8601 strings.

For non-ISO date formats, custom naming strategies, or other custom serialization, register your own `TypeMapper`:

``` java
var server = OpenApiServer.builder()
    .spec(spec)
    .bodyMapper("application/json", new MyCustomJsonMapper())
    .handlers(handlers)
    .build();
```

If Gson is not on the classpath and no `application/json` mapper is registered, `build()` throws `IllegalStateException`.

### Body parsers and response writers

`TypeMapper` is the per-media-type read/write contract:

``` java
public interface TypeMapper {
  Object readFrom(byte[] body, String contentTypeHeader);
  byte[] writeTo(Object value);
}
```

Register a custom mapper for any media type via `Builder.bodyMapper(mediaType, mapper)`. Built-in defaults:

- `application/x-www-form-urlencoded` — read-only. Produces `Map<String, Object>`. A single value is a `String`; repeated keys produce a `List`.
- `text/plain` — read and write. Produces a decoded `String`; writes via `String.getBytes()`.
- `application/json` — auto-registered when Gson is on the classpath (see above).

User-supplied mappers take precedence over built-in defaults, so you can override any of the above.

### Request body content types

The server reads `requestBody.content` from the spec and selects a mapper by the request's media type (the bare `type/subtype` from `Content-Type`, e.g. `application/json`; lookup is case-insensitive):

| Content type                          | Parser                                                                       | Coercion |
| ------------------------------------- | ---------------------------------------------------------------------------- | -------- |
| `application/json`                    | `GsonJsonMapper` (auto) or caller-supplied `TypeMapper`                      | No — strict against the schema |
| `application/x-www-form-urlencoded`   | Built-in. `Map<String, Object>`. A single value is a `String`; repeated keys produce a `List`. After coercion the element type tracks the schema (e.g. an `integer` array yields `List<Long>`). | Yes — field values coerced to the property schema type (integer / number / boolean / array of those) |
| `text/plain`                          | Built-in. Decoded `String`                                                   | No — schema should be `type: string` |

Form-field coercion mirrors the rules already used at the parameter boundary: the wire is string-only by definition, so a property typed as `integer` accepts `"42"` and yields `42`. Coercion failures surface as RFC-7807 `400` responses with a JSON-pointer to the failing field.

Both built-in parsers honour the `charset=` parameter on the `Content-Type` header (default UTF-8). Unknown charsets fall back to UTF-8.

### Error responses (RFC 7807)

Validation failures — missing required fields, type mismatches, unsupported content types, coercion errors, malformed bodies — produce an `HTTP 400 Bad Request` response with body media type `application/problem+json`, following [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807).

A single error is reported per request (first failure wins). The response body has these fields:

| Field      | Type    | Description                                                                              |
| ---------- | ------- | ---------------------------------------------------------------------------------------- |
| `type`     | string  | Always `about:blank` (no per-error type URI).                                            |
| `title`    | string  | Always `Bad Request`.                                                                    |
| `status`   | integer | Always `400`.                                                                            |
| `detail`   | string  | Human-readable description of the failure (e.g. `expected integer`).                     |
| `pointer`  | string  | [RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) JSON-Pointer to the failing location (e.g. `/body/age`, `/query/limit`, `/path/id`, or `/body` for body-wide errors). |
| `keyword`  | string  | The validation rule that failed: `type`, `required`, `enum`, `pattern`, `format`, `minimum`, `maximum`, `minLength`, `maxLength`, `additionalProperties`, `oneOf`, `anyOf`, `allOf`, `not`, `const`, `content-type`, `decode`, … |

Example body for `POST /form-echo` with `age=abc` (`age` is declared as `integer`):

``` json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "expected integer",
  "pointer": "/age",
  "keyword": "type"
}
```

Other error responses:

- **404 Not Found** — no route matches the request path (no body).
- **405 Method Not Allowed** — path matches but the HTTP method isn't declared. Includes an `Allow` header listing permitted methods (no body).
- **500 Internal Server Error** — uncaught exception from a handler. No body by default; override `ExceptionHandler` if you need a different envelope.

The error mapping is performed by `Handlers.defaultExceptionHandler()`. Pass your own `ExceptionHandler` to `OpenApiServer.builder().exceptionHandler(...)` if you need a different response shape (e.g. multi-error collection, custom problem types, locale-aware `detail`).

### Extra (non-OpenAPI) handlers

Mount handlers at arbitrary paths outside the OpenAPI spec — useful for liveness probes,
serving the spec document itself, or any other operational endpoint that should not be subject
to OpenAPI parameter / body validation.

``` java
var server = OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .addHandler("/alive", Handlers.aliveHandler())
    .addHandler("/schemas/v1/openapi.yaml",
                Handlers.specHandler("/schemas/v1/openapi.yaml"))
    .build();
```

Extra handlers bypass OpenAPI validation but are still wrapped in the configured
`ExceptionHandler`, so any uncaught exception is rendered using the same error envelope as
API routes.

Built-in helpers:
- `Handlers.aliveHandler()` — 204 No Content on `GET`/`HEAD`, 405 otherwise.
- `Handlers.specHandler(classpathResource)` — serves a classpath resource (content-type
  inferred from extension). Throws `IllegalArgumentException` at construction if the resource
  is missing.

The original public constructors remain available for back-compat.

### Graceful shutdown

`OpenApiServer` exposes `stop(int delaySeconds)` for explicit shutdown that waits up to the
given number of seconds for in-flight exchanges to complete before closing them. `0` stops
immediately. The same drain timeout can be wired into `close()` (and therefore
try-with-resources) via the builder:

```java
try (var server = OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .shutdownTimeoutSeconds(5)   // close() drains up to 5s; default is 0
    .build()) {
  // serve requests...
} // close() now waits up to 5s for in-flight exchanges
```

`stop(int)` and `shutdownTimeoutSeconds(int)` reject negative values with
`IllegalArgumentException`.

## Features
- OpenAPI specification support
- Automatic request body parsing and response writing per media type via `TypeMapper`
- `RequestHandler` functional interface — a single `handle(Request)` method replaces raw `HttpExchange` manipulation
- Fluent `ResponseBuilder` via `request.respond(status)` with terminals: `empty()`, `bytes()`, `text()`, `json()`, `body()`, `stream()`
- Built-in `GsonJsonMapper` auto-registered when Gson is on the classpath (no explicit wiring needed)
- Built on Java's native `HttpServer` with Thread-Per-Request behaviour using Virtual Threads


## Handler Registration
Handlers are registered in a `Map<String, RequestHandler>` keyed by OpenAPI `operationId`.

## Local development

To test the server in isolation, you can start an example server (`src/test/java/com/retailsvc/http/start/ServerLauncher.java`).
Schemas are located under test resources folder.

- Example requests can be found under `acceptance/k6` that can be a base for exploring the functionality.
- The logger in the configuration needs to be enabled to get some insight into the code.

## Performance and caveats

The library wraps the JDK's bundled `com.sun.net.httpserver.HttpServer` and uses a virtual-thread-per-request executor. On a developer laptop (Apple Silicon, single instance, default JVM flags) it sustains roughly:

- **~32k requests/second** for small JSON GETs and POSTs (~300 byte bodies), measured via `k6` at 30 sustained VUs over 45 seconds (1.4M requests, **100% of checks passing**, 0% HTTP failures).

A few things to know:

- **Single-process model.** No horizontal scaling primitives are bundled; run multiple instances behind a load balancer for production scale.
- **JDK HttpServer is the throughput ceiling.** It's documented as a low-throughput / dev-test server. If you need to go materially above the rates above, deploy the same filter/validator/router stack on Jetty, Helidon Níma, or Netty — the spec and validation code is server-agnostic.
- **Per-request state uses `ScopedValue`** (Java 25, JEP 506). This matters if a handler offloads work to an executor that's not a `StructuredTaskScope`-managed child thread: the `ScopedValue` is not visible there, so the handler must capture the values it needs (e.g. `byte[] body = request.bytes();`) before submitting.
- **Empty responses must use `request.respond(status).empty()`**, which sends `responseLength = -1` (`Content-Length: 0`, no body). Passing `0` produces a chunked response with zero chunks, which is technically non-conformant.

## Known limitations or missing features
