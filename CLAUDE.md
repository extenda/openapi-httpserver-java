# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A lightweight Java 25 library that wraps the JDK's built-in `com.sun.net.httpserver.HttpServer` and exposes endpoints declared in an OpenAPI 3.1.x specification. Consumers register `HttpHandler` instances by OpenAPI `operationId`. The library is published as a JAR; the example launcher under `src/test/java/.../start/ServerLauncher.java` is for local development only.

Java 25 is required (see `.java-version`). The server uses thread-per-request with virtual threads.

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
2. A single `HttpContext` is registered at `spec.basePath()` (the first `servers[].url` path from the OpenAPI doc). A catch-all `/` context returns 404.
3. Three filters run in order on every request:
    - `ExceptionFilter` — wraps the chain; delegates uncaught exceptions to the user-supplied `ExceptionHandler` (default in `Handlers`).
    - `RequestPreparationFilter` — reads the raw request body, stashes it as an exchange attribute, runs OpenAPI parameter + body validation via `DefaultValidator`, and stores the resolved `operationId` on the exchange.
    - `DispatchHandler` — looks up the `HttpHandler` registered for that `operationId` in the user-supplied map and invokes it. Handler coverage is verified at boot, so the lookup never returns `null`.

Key abstractions:

- `com.retailsvc.http.spec.Spec` — parsed from a consumer-supplied `Map<String, Object>` via `Spec.from(raw)`. No JSON library dependency in the library itself; callers use Gson, Jackson, SnakeYAML, etc. to produce the map.
- Sealed `com.retailsvc.http.spec.schema.Schema` interface with per-kind records (`StringSchema`, `NumberSchema`, `IntegerSchema`, `ArraySchema`, `ObjectSchema`, `BooleanSchema`, `NullSchema`, `AnyOfSchema`, `AllOfSchema`, `OneOfSchema`). Pattern-match dispatch eliminates instanceof chains.
- `com.retailsvc.http.validate.DefaultValidator` — single class using `switch` pattern-match over `Schema` subtypes. Validation failures produce RFC 7807 `application/problem+json` 400 responses.
- `com.retailsvc.http.internal.Router` — two indexes: exact path map and templated path list. Resolves `operationId` + extracted path variables for each request.
- `JsonMapper` — `@FunctionalInterface`; single method `Object mapFrom(byte[])`. Callers supply a lambda (see README).
- `com.retailsvc.http.Request` — static helper; `Request.bytes(exchange)` returns raw body bytes, `Request.parsed(exchange)` returns the `Object` produced by the `JsonMapper`.

## Conventions

- Code is formatted with the Google Java Formatter (enforced by pre-commit). Do not hand-format.
- Commit messages must satisfy commitlint (Conventional Commits).
- Integration tests are named `*IT.java` and run only under `mvn verify`, not `mvn test`.
- The library has `slf4j-api` as `provided` — never add a transitive logging binding to main scope.
