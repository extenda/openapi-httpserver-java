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
2. One `HttpContext` is registered per spec binding at `spec.basePath()` (the first `servers[].url` path from the OpenAPI doc). Unless a binding owns `/`, a catch-all `/` context serves extra routes via `ExtrasRouter` and 404s everything else; `ExceptionFilter` wraps that context only.
3. On a binding context, two filters run in order, then the handler:
    - `RequestPreparationFilter` — reads the request body through `RequestBodyReader` (which decodes a registered `Content-Encoding` — gzip is built in — under a size cap), resolves the route, runs OpenAPI parameter + body validation via `DefaultValidator`, and binds the resulting `Request` into the `DispatchHandler.CURRENT` scoped value. It renders its own failures through the `ExceptionHandler` rather than relying on `ExceptionFilter`.
    - `SecurityFilter` — enforces the spec's `securitySchemes` / `security`, re-binding the `Request` with resolved principals. It writes its 401/403 responses straight to the exchange.
    - `DispatchHandler` — looks up the `RequestHandler` registered for the resolved `operationId` in the user-supplied map and invokes it, applying interceptors and response decorators. Handler coverage is verified at boot, so the lookup never returns `null`.

Every response except `SecurityFilter`'s rejections is written by `ResponseRenderer`, which is also where response content coding is applied.

Key abstractions:

- `com.retailsvc.http.spec.Spec` — parsed from a consumer-supplied `Map<String, Object>` via `Spec.from(raw)`. No JSON library dependency in the library itself; callers use Gson, Jackson, SnakeYAML, etc. to produce the map.
- Sealed `com.retailsvc.http.spec.schema.Schema` interface with per-kind records (`StringSchema`, `NumberSchema`, `IntegerSchema`, `ArraySchema`, `ObjectSchema`, `BooleanSchema`, `NullSchema`, `AnyOfSchema`, `AllOfSchema`, `OneOfSchema`). Pattern-match dispatch eliminates instanceof chains.
- `com.retailsvc.http.validate.DefaultValidator` — single class using `switch` pattern-match over `Schema` subtypes. Validation failures produce RFC 9457 `application/problem+json` 400 responses.
- `com.retailsvc.http.internal.Router` — two indexes: exact path map and templated path list. Resolves `operationId` + extracted path variables for each request.
- `TypeMapper` — per-media-type request parsing and response writing; registered via `Builder.bodyMapper(...)`, with `GsonTypeMapper` auto-registered when Gson is on the classpath.
- `com.retailsvc.http.Request` — an immutable record-like carrier built from primitives (body bytes, path parameters, raw query string, a header lookup function), never the `HttpExchange`. `bytes()` returns the decoded body, `parsed()` the object produced by the `TypeMapper`.
- `com.retailsvc.http.ContentCoding` — a pluggable HTTP content coding. gzip is built in (`internal/GzipCoding`); callers register others on the builder, held per direction in `internal/ContentCodings`. `RequestBodyReader` decodes requests under the size cap and `ResponseRenderer` codes responses. See the README's "Content encoding" section for the policy.

## Conventions

- Code is formatted with the Google Java Formatter (enforced by pre-commit). Do not hand-format.
- Commit messages must satisfy commitlint (Conventional Commits).
- Integration tests are named `*IT.java` and run only under `mvn verify`, not `mvn test`.
- The library has `slf4j-api` as `provided` — never add a transitive logging binding to main scope.
