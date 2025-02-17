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
- Java SDK 21 or later
- A serialization library, e.g. Gson or Jackson
- OpenAPI specification file in JSON format (`openapi.json`)


### Basic Usage
1. Create an OpenAPI specification file named `openapi.json` in your project resources.
2. Define your HTTP handlers by implementing the `HttpHandler` interface:
``` java
public class GetDataHandler implements HttpHandler {
  // Implement your POST endpoint logic

  // Example
  try (exchange) {
    byte[] bytes = """
    {
      "id": "some-id"
    }""".getBytes();

    try (var os = exchange.getResponseBody()) {
      var responseHeaders = exchange.getResponseHeaders();
      responseHeaders.add("content-type", "application/json");

      exchange.sendResponseHeaders(HTTP_OK, bytes.length);

      os.write(bytes);
    }
  }
}

public class PostDataHandler implements HttpHandler, GetRequestBody {
  // Implement your POST endpoint logic
}
```

1. Initialize the server (using Gson in this example):
``` java
public class YourServerLauncher {
  public static void main(String[] args) throws Exception {
    final Gson gson = new Gson();

    // Parse OpenAPI specification (or build your instance of OpenApi manually)
    var specification = parseSpecification("openapi.json",  s -> gson.fromJson(s, OpenApi.class));

    // Register your handlers (operation-id -> handler)
    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());

    // Create JSON mapper (supports both arrays and objects)
    JsonMapper mapper = new JsonMapper() {
        @Override
        public <T> T mapFrom(byte[] body) {
          if (body.length > 0 && body[0] == '[') {
            return (T) gson.fromJson(new String(body), List.class);
          }
          return (T) gson.fromJson(new String(body), Map.class);
        }
    };

    ExceptionHandler exceptionHandler = Handlers.defaultExceptionHandler();

    // Start the server
    new OpenApiServer(specification, mapper, handlers, exceptionHandler);
  }
}
```

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

## Known limitations (not exhaustive..)

- OpenAPI refs are not supported yet.
