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
2. Define your HTTP handlers by implementing the `HttpHandler` interface:
``` java
public class GetDataHandler implements HttpHandler {
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] bytes = """
      {
        "id": "some-id"
      }""".getBytes();

      var responseHeaders = exchange.getResponseHeaders();
      responseHeaders.add("content-type", "application/json");

      exchange.sendResponseHeaders(HTTP_OK, bytes.length);

      try (var os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }
}

public class PostDataHandler implements HttpHandler {
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      // Access the raw request body bytes.
      byte[] body = Request.bytes(exchange);
      // Or get the already-parsed object (Map or List) produced by your JsonMapper.
      Object parsed = Request.parsed(exchange);

      exchange.sendResponseHeaders(HTTP_OK, -1);
    }
  }
}
```

3. Initialize the server (using Gson in this example):
``` java
public class YourServerLauncher {
  public static void main(String[] args) throws Exception {
    Gson gson = new Gson();

    // Parse spec to a generic Map (works for JSON; for YAML use SnakeYAML).
    String text = Files.readString(Path.of("openapi.json"));
    Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
    Spec spec = Spec.from(raw);

    // Body parser. Returns a Map for objects, List for arrays.
    JsonMapper mapper = body -> gson.fromJson(new String(body), Object.class);

    // Handlers by operationId.
    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());

    var server = OpenApiServer.builder()
        .spec(spec)
        .jsonMapper(mapper)
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

### Extra (non-OpenAPI) handlers

Mount handlers at arbitrary paths outside the OpenAPI spec — useful for liveness probes,
serving the spec document itself, or any other operational endpoint that should not be subject
to OpenAPI parameter / body validation.

``` java
var server = OpenApiServer.builder()
    .spec(spec)
    .jsonMapper(mapper)
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
    .jsonMapper(mapper)
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
- Automatic request body parsing for JSON arrays and objects
- Custom HTTP handler support
- Built on Java's native `HttpServer` with Thread-Per-Request behaviour using Virtual Threads.
- Custom integration for JSON serialization/deserialization


## Handler Registration
Handlers are registered using string keys that correspond to your OpenAPI operation IDs.


## JSON Mapping
The library uses a flexible JSON mapping system that automatically detects and parses (using a mapper of choice):
- JSON arrays (`[...]`)
- JSON objects (`{...}`)

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
- **Per-request state uses `ScopedValue`** (Java 25, JEP 506), not `HttpExchange.setAttribute`. This matters if a handler offloads work to an executor that's not a `StructuredTaskScope`-managed child thread: the `ScopedValue` is not visible there, so the handler must capture the values it needs (e.g. `byte[] body = Request.bytes();`) before submitting.
- **`HttpExchange.sendResponseHeaders(rCode, length)` gotcha.** When a handler has no response body, pass `-1` (`Content-Length: 0`, no body); passing `0` produces a chunked response with zero chunks, which is technically non-conformant.

## Known limitations or missing features
