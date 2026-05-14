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
- Java SDK 25 or later.
- A JSON library to parse the spec into a `Map<String, Object>`: any of Gson, Jackson, SnakeYAML (for YAML specs), or another mapper of your choice. The library itself doesn't bundle one.
- An OpenAPI 3.1.x specification (`openapi.json` or `openapi.yaml`).
- For `application/json` request/response bodies, either:
  - Gson on the classpath — auto-registered via the built-in `GsonJsonMapper` (integer-preserving, JSR-310 written as ISO-8601), or
  - Jackson via the built-in `JacksonJsonTypeMapper(ObjectMapper)` adapter (caller supplies a configured `ObjectMapper`), or
  - any other `TypeMapper` you register via `Builder.jsonMapper(mapper)` (shortcut for `bodyMapper("application/json", mapper)`).
- Built-in mappers for `application/x-www-form-urlencoded` and `text/plain` need no configuration. Any other media type (`application/xml`, `application/cbor`, etc.) requires registering its own `TypeMapper`.


### Basic Usage
1. Create an OpenAPI specification file named `openapi.json` in your project resources.
2. Define your handlers using the `RequestHandler` functional interface. Handlers are pure functions: they consume a `Request` and return a `Response`. The framework renders the response (status code, headers, body) for you.
``` java
// Inline lambda — returns JSON using the built-in Gson mapper.
RequestHandler getDataHandler = req -> Response.ok(Map.of("id", "some-id"));

// Class form — reads raw bytes, the loose Map view, or a typed POJO.
public class PostDataHandler implements RequestHandler {
  @Override
  public Response handle(Request request) {
    // Access the raw request body bytes.
    byte[] body = request.bytes();
    // Loose structural view (Map / List / boxed primitives), produced by the registered TypeMapper.
    Object parsed = request.parsed();
    // Or get a typed POJO directly (works with the Gson and Jackson built-ins; both implement
    // TypedTypeMapper).
    MyDto dto = request.asPojo(MyDto.class);
    // Path parameters, query parameters, and headers are also available.
    String id = request.pathParam("id");                     // null if absent
    Optional<String> filter = request.queryParam("filter");  // empty if absent or blank
    Optional<String> corr = request.header("correlation-id");

    return Response.ok(dto);
  }
}
```

### Building responses

`Response` is an immutable record built via static factories. Pick the one that fits:

``` java
Response.empty();                                 // 204 No Content, no body
Response.status(200);                             // 200 OK, no body
Response.ok(Map.of("id", "42"));                  // 200 OK, JSON body via TypeMapper
Response.created(newResource);                    // 201 Created, JSON body
Response.created(newResource, "/things/42");      // 201 Created + Location header
Response.accepted();                              // 202 Accepted, no body
Response.accepted(Map.of("jobId", "job-42"));     // 202 Accepted, JSON body
Response.notFound();                              // 404 Not Found, no body
Response.notFound(problemDetail);                 // 404 Not Found, JSON body
Response.notImplemented();                        // 501 Not Implemented, no body
Response.of(409, conflictDetail);                 // any status, JSON body
Response.text(200, "hello");                      // text/plain; UTF-8
Response.bytes(200, pdf, "application/pdf");      // pre-serialised bytes
Response.stream(200, "application/octet-stream",  // chunked streaming
    out -> out.write(largeBlob));
Response.stream(200, length, "application/pdf",   // sized streaming
    out -> pipeFromBackend(out));
```

Add or modify pieces non-destructively:

``` java
return Response.ok(payload)
    .withHeader("X-Tenant-Id", tenant)
    .withContentType("application/vnd.example+json");
```

A `null` body always produces a status-only response (`Content-Length: 0`, no body bytes), regardless of status code. Streaming bodies bypass `TypeMapper` entirely; one-shot object bodies (`ok`, `of`) are serialised by the `TypeMapper` registered for the response's content type (default `application/json`).

3. Initialize the server:
``` java
public class YourServerLauncher {
  public static void main(String[] args) throws Exception {
    // Gson is on the classpath, so we can load the spec in one line.
    Spec spec = Spec.fromPath(Path.of("openapi.json"));

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

`Spec.fromPath(Path)` picks the parser by file extension: `.json` is parsed by Gson, `.yaml` / `.yml` by SnakeYAML. Both are optional dependencies of this library — the same Gson that powers the built-in JSON `TypeMapper`, and the same SnakeYAML you'd add explicitly to parse YAML. If the required parser isn't on the classpath the call fails with `IllegalStateException`; parse the file yourself and use `Spec.from(Map<String, Object>)` instead. Any other extension is rejected.

### JSON mapping

The library ships an internal `GsonJsonMapper` that is auto-registered for `application/json` when Gson is on the classpath and no user-supplied JSON mapper has been registered. It:

- Returns JSON integers as `Long` and fractional numbers as `Double` for the loose `request.parsed()` view.
- For `request.asPojo(MyDto.class)`, delegates to Gson — the target type's fields determine the Java types (`int`, `long`, `Instant`, etc.).
- Round-trips JSR-310 types (`Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime`, `LocalDate`, `LocalTime`) as their ISO-8601 string form.

For Jackson, the library ships a `JacksonJsonTypeMapper` adapter that wraps an `ObjectMapper` you configure (modules, naming strategy, JSR-310, date formats — all your call):

``` java
ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

var server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(new JacksonJsonTypeMapper(objectMapper))
    .handlers(handlers)
    .build();
```

The same shape applies to any custom mapper — implement `TypeMapper` (and optionally `TypedTypeMapper` if you can deserialise directly into a target type, so handlers can call `request.asPojo(MyDto.class)`).

If neither Gson is on the classpath nor any `application/json` mapper is registered, `build()` throws `IllegalStateException`.

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

### Response decorators

`Builder.responseDecorator(...)` registers a `ResponseDecorator` — a `(Request, Response) -> Response` transform applied to every handler's return value before rendering. Decorators compose in registration order: the result of one is fed to the next. Decorator-supplied headers override handler-supplied ones; if you want the opposite, set the header inside the handler with `Response.withHeader(...)`.

``` java
OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .responseDecorator((req, resp) -> resp.withHeader("X-Correlation-Id", CorrelationId.current()))
    .responseDecorator((req, resp) -> resp.withHeader("X-Tenant-Id", TenantId.current()))
    .build();
```

### Request interceptors

`Builder.interceptor(...)` registers a `RequestInterceptor` that wraps every handler invocation. Use it for `ScopedValue` bindings, MDC, authentication, tracing, or any concern that needs to run uniformly around handlers. Interceptors compose in registration order: the first registered runs outermost. Each interceptor must call `next.proceed()` and return the result (or a transformed `Response`).

``` java
OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .interceptor((request, next) -> {
      // Resolve once per request; bind to a ScopedValue for the rest of the chain.
      String tenant = request.header("X-Tenant-Id").orElse("public");
      return ScopedValue.where(TENANT, tenant).call(next::proceed);
    })
    .interceptor((request, next) -> {
      MDC.put("op", request.operationId());
      try {
        return next.proceed();
      } finally {
        MDC.remove("op");
      }
    })
    .build();
```

Exceptions propagate to the library's standard `ExceptionFilter` and `ExceptionHandler` pipeline.

### Combining interceptors and decorators

The two collaborate naturally: the interceptor binds per-request context once, and the decorator reads that context when stamping response headers. Handlers stay pure business logic.

``` java
// Per-request context populated by the interceptor, read by the decorator and handlers.
ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
ScopedValue<String> TENANT_ID = ScopedValue.newInstance();

OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    // 1. Resolve once per request and bind to ScopedValues.
    .interceptor((request, next) -> {
      String correlationId =
          request.header("X-Correlation-Id").orElseGet(() -> UUID.randomUUID().toString());
      String tenantId = resolveTenant(request);
      return ScopedValue.where(CORRELATION_ID, correlationId)
          .where(TENANT_ID, tenantId)
          .call(next::proceed);
    })
    // 2. Stamp those values on every response.
    .responseDecorator((req, resp) -> resp
        .withHeader("X-Correlation-Id", CORRELATION_ID.get())
        .withHeader("X-Tenant-Id", TENANT_ID.get()))
    .build();
```

Decorators run inside the interceptor's `ScopedValue` binding (the decorator transforms the `Response` returned by `next.proceed()`, which is still on the call stack), so `CORRELATION_ID.get()` / `TENANT_ID.get()` see the bound values.

A handler in this setup is just business logic:

``` java
public class GetPromotionHandler implements RequestHandler {
  @Override
  public Response handle(Request request) {
    String id = request.pathParam("id");
    String tenant = TENANT_ID.get();
    return promotionService
        .find(tenant, id)
        .<Response>map(Response::ok)
        .orElseGet(Response::notFound);
  }
}
```

### End-to-end example

Gson on the classpath for request/response JSON, SnakeYAML on the classpath for the spec, one interceptor binding a request-scoped tenant + correlation id, one decorator stamping the correlation id on every response, one handler. No extra wiring.

``` java
package com.example.promotions;

import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.Spec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class App {

  static final ScopedValue<String> TENANT = ScopedValue.newInstance();
  static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

  public static void main(String[] args) throws Exception {
    Spec spec = Spec.fromPath(Path.of("openapi.yaml"));         // SnakeYAML parses the spec

    RequestHandler getPromotion = req -> {
      String id = req.pathParam("id");
      return PromotionService.find(TENANT.get(), id)            // uses bound tenant
          .<Response>map(Response::ok)                           // 200 + JSON via Gson
          .orElseGet(Response::notFound);                        // 404, no body
    };

    OpenApiServer.builder()
        .spec(spec)
        .handlers(Map.of("get-promotion", getPromotion))
        // Bind tenant + correlation id once per request.
        .interceptor((req, next) -> {
          String tenant = req.header("X-Tenant-Id").orElse("public");
          String correlationId =
              req.header("X-Correlation-Id").orElseGet(() -> UUID.randomUUID().toString());
          return ScopedValue.where(TENANT, tenant)
              .where(CORRELATION_ID, correlationId)
              .call(next::proceed);
        })
        // Stamp the correlation id on every response.
        .responseDecorator((req, resp) -> resp.withHeader("X-Correlation-Id", CORRELATION_ID.get()))
        .port(8080)
        .build();
  }
}
```

What the example demonstrates:

- **Gson is the default JSON serializer.** No explicit `bodyMapper(...)` call — the library auto-registers `GsonJsonMapper` for request and response JSON because Gson is on the classpath.
- **SnakeYAML parses the spec.** `Spec.fromPath(...)` picks the parser by file extension; `.yaml` here means SnakeYAML, and Gson would handle `.json` the same way.
- **One interceptor sets cross-cutting context.** `ScopedValue.where(...).call(next::proceed)` runs the handler (and any inner interceptors and decorators) inside the binding, so `TENANT.get()` and `CORRELATION_ID.get()` work anywhere they're called.
- **One decorator stamps a response header.** `Response.withHeader(...)` is non-destructive — the handler's `Response` is replaced with one that has the extra header.
- **Handler is a pure function.** Reads from `Request`, returns a `Response` value. No `HttpExchange`, no try/catch IOException, no builder.

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
    .extraRoute("/alive", Handlers.aliveHandler())
    .extraRoute("/schemas/v1/openapi.yaml",
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
- Handlers are pure functions: `Response handle(Request)`. Factories cover `empty()` / `status(int)` / `ok(Object)` / `of(int, Object)` / `text(int, String)` / `bytes(int, byte[], String)` / `stream(...)`
- Built-in `GsonJsonMapper` auto-registered when Gson is on the classpath (no explicit wiring needed)
- `ResponseDecorator` for cross-cutting response headers and `RequestInterceptor` for around-style ScopedValue / MDC / auth concerns
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
- **Empty responses use `Response.empty()` (204) or `Response.status(code)` for other no-body statuses.** The renderer sends `responseLength = -1` (`Content-Length: 0`, no body) for any `Response` with `body() == null`, regardless of status code. Passing `0` to the JDK directly produces a chunked response with zero chunks, which is technically non-conformant — `Response` factories handle this for you.

## Known limitations or missing features
