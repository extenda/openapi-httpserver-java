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

    new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());
  }
}
```

### YAML specifications
For YAML, replace the JSON parsing line with SnakeYAML:
``` java
Map<String, Object> raw = new Yaml().load(Files.newInputStream(Path.of("openapi.yaml")));
```
The rest is identical.

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

## Known limitations or missing features
