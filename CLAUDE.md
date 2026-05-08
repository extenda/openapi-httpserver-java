# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A lightweight Java 21 library that wraps the JDK's built-in `com.sun.net.httpserver.HttpServer` and exposes endpoints declared in an OpenAPI 3.1.x specification. Consumers register `HttpHandler` instances by OpenAPI `operationId`. The library is published as a JAR; the example launcher under `src/test/java/.../start/ServerLauncher.java` is for local development only.

Java 21 is required (see `.java-version`). The server uses thread-per-request with virtual threads.

## Common commands

- Build: `mvn package`
- Unit tests (Surefire): `mvn test`
- Integration tests (Failsafe, `*IT.java`): `mvn verify`
- Single test class: `mvn test -Dtest=OpenApiServerTest`
- Single test method: `mvn test -Dtest=OpenApiServerTest#methodName`
- Coverage report: produced at `target/site/jacoco/` after `mvn verify`
- POM is sort-checked by `sortpom-maven-plugin` during `validate`; fix with `mvn sortpom:sort`
- Pre-commit hooks (Google Java formatter, commitlint, editorconfig, etc.) run via `pre-commit`; install with `pre-commit install --hook-type pre-commit --hook-type commit-msg`
- Run example server locally: `mvn test-compile exec:java -Dexec.mainClass=com.retailsvc.http.start.ServerLauncher -Dexec.classpathScope=test` (or run `ServerLauncher` from the IDE). Test schema lives at `src/test/resources/openapi.json`.
- Acceptance/load probes: k6 scripts under `acceptance/k6/`. ZAP scan via `./zap.sh`.

## Architecture

Request flow when `OpenApiServer` boots (`src/main/java/com/retailsvc/http/OpenApiServer.java`):

1. `HttpServer` is created on a port with a virtual-thread-per-task executor.
2. A single `HttpContext` is registered at `specification.basePath()` (the first `servers[].url` path from the OpenAPI doc). A catch-all `/` context returns 404.
3. Three filters run in order on every request:
    - `ExceptionHandlingFilter` — wraps the chain; delegates uncaught exceptions to the user-supplied `ExceptionHandler` (default in `Handlers`).
    - `BodyHandler` — reads the raw request body and stashes it as an exchange attribute so handlers/validators can read it without re-consuming the stream.
    - `OpenApiValidationFilter` — matches the request to an `Operation` from the spec, validates path/query parameters and body against the schema (`com.retailsvc.http.openapi.validation.*`), and stores the resolved `operationId` on the exchange.
4. `RequestDispatchingHandler` looks up the `HttpHandler` registered for that `operationId` in the user-supplied map and invokes it. Missing handler → `MissingOperationHandlerException`.

Key abstractions:

- `com.retailsvc.http.openapi.model.OpenApi` and siblings (`PathItem`, `Operation`, `Parameter`, `Schema`, `MediaType`, …) are plain records/POJOs that the consumer's JSON library (Gson, Jackson, …) deserializes into. The library does not pull in a JSON dependency.
- `JsonMapper` is the consumer-supplied bridge for parsing request bodies. Implementations must handle both top-level JSON objects and arrays (see README example).
- `GetRequestBody` is a marker-style interface handlers implement when they need access to the parsed body via the exchange attribute set by `BodyHandler`.
- Validators in `openapi/validation/` are per-type (`StringValidator`, `NumberValidator`, `ArrayValidator`, `ObjectValidator`, `BooleanValidator`) composed by `ValidatorImpl`. Validation failures throw `BadRequestException` / `BadRequestTypeException` which the default exception handler maps to 4xx.
- `SpecificationLoader.parseSpecification(String, Function<String, OpenApi>)` reads a classpath resource and hands the JSON string to the consumer's parser.

## Conventions

- Code is formatted with the Google Java Formatter (enforced by pre-commit). Do not hand-format.
- Commit messages must satisfy commitlint (Conventional Commits).
- Integration tests are named `*IT.java` and run only under `mvn verify`, not `mvn test`.
- The library has `slf4j-api` as `provided` — never add a transitive logging binding to main scope.
