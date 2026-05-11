# OpenAPI HTTP Server — Refactor & 3.1 Readiness

**Date:** 2026-05-07
**Status:** Approved (pending final spec review)
**Target Java release:** 25
**Public API stability:** breaking changes accepted

## Goal

Restructure the library so that filling OpenAPI 3.1 gaps becomes a mechanical, per-keyword exercise rather than a structural rewrite. This design covers the refactor itself; specific 3.1 keywords are tracked as a prioritized follow-up list (Section 9) — each becomes its own small spec.

The refactor also folds in 3.1 keywords that are effectively free once the typed schema model exists (size/multiple-of constraints, nullable via `["type","null"]`, etc.). Anything that needs more than a one-line validator branch is punted.

## Decisions (locked during brainstorming)

1. **Goal shape:** restructure for 3.1 readiness; document remaining gaps for iteration.
2. **API stability:** break freely. Library is at `0.0.1-local`; release pipeline not yet enabled.
3. **Spec parsing:** consumer parses spec text to `Map<String,Object>` with their own JSON/YAML library; library walks the map into a typed model. The current `Function<String, OpenApi>` parser callback and the `fix/support-yaml` `Function<Object,String> toJson` callback both go away.
4. **Validation contract:** validators throw `ValidationException` carrying a structured `ValidationError`. Default `ExceptionHandler` renders it as RFC 7807 `application/problem+json`, status 400.
5. **Schema model:** sealed interface with one record per kind (`StringSchema`, `ObjectSchema`, …). Combinators (`OneOfSchema` etc.) are scaffolded but throw `UnsupportedOperationException` from the validator until follow-up specs implement them.
6. **`AdditionalProperties`:** small wrapper sealed type (`Allowed` / `Forbidden` / `SchemaConstraint`).
7. **`format`:** stays a free-form `String`; validators handle known formats and ignore unknowns (warn-log once per unknown format).
8. **Java target:** 25.
9. **Runtime dependencies:** none added. SLF4J stays `provided`.

## High-level architecture

Request lifecycle:

```
HTTP request
  └── ExceptionFilter
        └── RequestPreparationFilter      (replaces BodyHandler + OpenApiValidationFilter)
              ├── read body bytes → exchange attribute "body"
              ├── Router.match(method, path) → Operation + path parameters
              │     │  miss → NotFoundException (404)
              │     │  method mismatch → MethodNotAllowedException (405)
              ├── validate path / query / header parameters
              ├── if RequestBody present: parse via JsonMapper, validate against MediaType.schema
              │     parsed body → exchange attribute "parsed-body"
              └── DispatchHandler
                    └── handlers.get(operationId).handle(exchange)
                          missing → MissingOperationHandlerException (500)
```

`ExceptionFilter` translates the typed exceptions to status codes (404 / 405 / 400 / 500) via the consumer's `ExceptionHandler`. The default exception handler renders RFC 7807 problem details for `ValidationException` and empty bodies for the others.

## Package layout

- `com.retailsvc.http` — public entry: `OpenApiServer`, `ExceptionHandler`, `JsonMapper`, `Handlers`, `Request`, `ValidationException`, `NotFoundException`, `MethodNotAllowedException`, `MissingOperationHandlerException`.
- `com.retailsvc.http.spec` — `Spec`, `Operation`, `Parameter`, `RequestBody`, `MediaType`, `Server`, `Info`, `PathTemplate`, `HttpMethod`.
- `com.retailsvc.http.spec.schema` — sealed `Schema` hierarchy, `AdditionalProperties`, `TypeName`, `SchemaParser`.
- `com.retailsvc.http.validate` — `Validator`, `DefaultValidator`, `ValidationError`.
- `com.retailsvc.http.internal` — `ExceptionFilter`, `RequestPreparationFilter`, `DispatchHandler`, `Router`, problem-detail renderer (package-private to consumers).

Removed (compared to current master):

- `BodyHandler` and `BodyHandler.RequestBodyWrapper`
- `GetRequestBody` interface
- `OpenApiValidationFilter`, `ExceptionHandlingFilter`, `RequestDispatchingHandler` (their roles move into `internal`)
- `SpecificationLoader`
- `Components`, `PathItem`, `OpenApi`, `OpenApiConstants` (collapsed into `Spec`)
- `ValidatorImpl`, `StringValidator`, `ObjectValidator`, `ArrayValidator`, `NumberValidator`, `BooleanValidator` (replaced by single `DefaultValidator` with pattern-match dispatch)
- `OperationIdNotFoundException`, `BadRequestException`, `BadRequestTypeException`, `NotFoundTypeException`, `LoadSpecificationException`, `NoServersDeclaredException`, `UnsupportedVersionException` (replaced by the new exception hierarchy and `ValidationError` messages)

## Schema model

```java
package com.retailsvc.http.spec.schema;

public sealed interface Schema
    permits StringSchema, NumberSchema, IntegerSchema, BooleanSchema,
            ObjectSchema, ArraySchema, NullSchema, RefSchema,
            OneOfSchema, AnyOfSchema, AllOfSchema, NotSchema,
            ConstSchema, EnumSchema {

  /** Type names declared on the schema. Empty for combinators, refs, const, enum. */
  Set<TypeName> types();
}

public enum TypeName { STRING, NUMBER, INTEGER, BOOLEAN, OBJECT, ARRAY, NULL }

public record StringSchema(
    Set<TypeName> types,
    String pattern,
    Integer minLength, Integer maxLength,
    String format,
    List<String> enumValues
) implements Schema {}

public record NumberSchema(
    Set<TypeName> types,
    Number minimum, Number maximum,
    Number exclusiveMinimum, Number exclusiveMaximum,
    Number multipleOf,
    String format
) implements Schema {}

public record IntegerSchema(
    Set<TypeName> types,
    Long minimum, Long maximum,
    Long exclusiveMinimum, Long exclusiveMaximum,
    Long multipleOf,
    String format
) implements Schema {}

public record ObjectSchema(
    Set<TypeName> types,
    Map<String, Schema> properties,
    List<String> required,
    AdditionalProperties additionalProperties,
    Integer minProperties, Integer maxProperties
) implements Schema {}

public record ArraySchema(
    Set<TypeName> types,
    Schema items,
    Integer minItems, Integer maxItems,
    boolean uniqueItems
) implements Schema {}

public record BooleanSchema(Set<TypeName> types) implements Schema {}
public record NullSchema()             implements Schema { /* types() = {NULL} */ }
public record RefSchema(String pointer) implements Schema { /* types() = {} */ }

public record OneOfSchema(List<Schema> options) implements Schema { /* scaffold */ }
public record AnyOfSchema(List<Schema> options) implements Schema { /* scaffold */ }
public record AllOfSchema(List<Schema> parts)   implements Schema { /* scaffold */ }
public record NotSchema(Schema schema)          implements Schema { /* scaffold */ }

public record ConstSchema(Object value)         implements Schema { /* scaffold */ }
public record EnumSchema(List<Object> values)   implements Schema { /* scaffold */ }

public sealed interface AdditionalProperties {
  record Allowed()                       implements AdditionalProperties {}  // default
  record Forbidden()                     implements AdditionalProperties {}  // false
  record SchemaConstraint(Schema schema) implements AdditionalProperties {}  // schema
}
```

`SchemaParser` (zero-dep, ~80–120 lines) walks `Map<String,Object>` and dispatches in this order:

1. `$ref` present → `RefSchema`
2. `oneOf` / `anyOf` / `allOf` / `not` present → corresponding combinator
3. `const` / `enum` (top-level) → `ConstSchema` / `EnumSchema`
4. `type` (string or array of strings; legacy `nullable: true` folds `NULL` into the set) → primitive record
5. No identifying keyword → permissive `ObjectSchema` (matches 3.1 default)

Refs are resolved on use, not at parse, so cycles and forward references are non-issues. `Components.getSchema`'s memoization is preserved at the `Spec.resolveSchema` layer.

## Spec model

```java
package com.retailsvc.http.spec;

public record Spec(
    String openapi,
    Info info,
    List<Server> servers,
    List<Operation> operations,
    Map<String, Schema> componentSchemas,
    Map<String, Parameter> componentParameters) {

  public static Spec from(Map<String,Object> raw);  // entry point

  public String basePath();                         // first server's path component
  public Schema resolveSchema(String ref);          // walks #/components/schemas/...
  public Parameter resolveParameter(String ref);    // walks #/components/parameters/...
}

public record Operation(
    String operationId,
    HttpMethod method,
    PathTemplate path,
    Optional<RequestBody> requestBody,
    List<Parameter> parameters,
    Map<String, Response> responses);

public record Parameter(
    String name,
    Location in,
    boolean required,
    Schema schema) {
  public enum Location { PATH, QUERY, HEADER, COOKIE }
}

public record RequestBody(boolean required, Map<String, MediaType> content);
public record MediaType(Schema schema);
public record Response(/* placeholder; populated when response validation lands */);
public enum HttpMethod { GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, CONNECT }

public record PathTemplate(String raw, Pattern compiled, List<String> parameterNames) {
  public Optional<Map<String,String>> match(String requestPath);  // returns extracted params
}
```

Key changes vs. master:

- `Spec.operations()` is a flat list. Each `Operation` carries its own method + `PathTemplate`.
- `PathItem` is gone as a public concept.
- `Parameter.in` is an `enum`, not a string. `Parameter.isHeader()` / `isQuery()` / `isPath()` helpers are replaced by enum comparison or pattern-match.
- `PathTemplate` owns path-parameter logic. Built once at parse: stores raw template (`/users/{id}`), compiled regex (`^/users/([^/]+)$`), and parameter names in order.
- A small JSON Pointer parser replaces ad-hoc `replace("#/components/...", "")` so future ref targets (`#/components/responses/...`, `#/components/parameters/...`) work uniformly.

## Routing

```java
package com.retailsvc.http.internal;

final class Router {
  // Built once from List<Operation>. Two indexes:
  //   - exact:     Map<HttpMethod, Map<String, Operation>>     (no path params)
  //   - templated: Map<HttpMethod, List<TemplateRoute>>        (regex match per route)
  Optional<Match> match(HttpMethod method, String path);
  record Match(Operation operation, Map<String,String> pathParameters) {}
}
```

Templated routes are scanned linearly with first-match-wins. Trie/sorted-by-specificity is a documented future optimization.

Routing outcomes:
- exact path + exact method → match
- known path, wrong method → `MethodNotAllowedException(allowed)` → 405
- unknown path → `NotFoundException` → 404

## Validation

```java
package com.retailsvc.http.validate;

public record ValidationError(
    String pointer,        // RFC 6901 JSON Pointer, e.g. "/user/email", "/query/page"
    String keyword,        // "type", "required", "minLength", "pattern", "format", "route"
    String message,
    Object rejectedValue   // omitted from default 7807 response unless consumer opts in
);

public interface Validator {
  /** Throws ValidationException on first failure. */
  void validate(Object value, Schema schema, String pointer);
}

public final class DefaultValidator implements Validator {
  public DefaultValidator(Spec spec);
  // single class; per-kind logic is private methods dispatched via pattern-match switch
}
```

Pattern-match dispatch:

```java
switch (schema) {
  case RefSchema r       -> validate(value, spec.resolveSchema(r.pointer()), pointer);
  case StringSchema s    -> validateString(value, s, pointer);
  case IntegerSchema i   -> validateInteger(value, i, pointer);
  case NumberSchema n    -> validateNumber(value, n, pointer);
  case BooleanSchema b   -> validateBoolean(value, b, pointer);
  case ObjectSchema o    -> validateObject(value, o, pointer);
  case ArraySchema a     -> validateArray(value, a, pointer);
  case NullSchema n      -> require(value == null, pointer, "type", "expected null");
  case EnumSchema e      -> require(e.values().contains(value), pointer, "enum", ...);
  case ConstSchema c     -> require(Objects.equals(c.value(), value), pointer, "const", ...);
  case OneOfSchema, AnyOfSchema, AllOfSchema, NotSchema ->
      throw new UnsupportedOperationException("combinator not yet supported");
}
```

Failure mode is fail-fast with a single `ValidationError`. Multi-error collection is a documented future option (additive).

JSON Pointer prefixes used for non-body validation:
- `/path/<name>` for path parameters
- `/query/<name>` for query parameters
- `/headers/<name>` for header parameters
- (request body errors use the natural `/...` pointer into the body)

## Default error rendering

`Handlers.defaultExceptionHandler()` adds branches for the new exception types. Hand-rolled JSON output (no dependency).

```
ValidationException        → 400 application/problem+json (RFC 7807)
                              { "type": "about:blank",
                                "title": "Bad Request",
                                "status": 400,
                                "detail": <message>,
                                "pointer": <pointer>,
                                "keyword": <keyword> }
NotFoundException          → 404, empty body
MethodNotAllowedException  → 405, "Allow" header set from allowed()
MissingOperationHandlerException → 500, empty body
other Throwable            → 500, empty body (logged)
```

`rejectedValue` is omitted from the default response. Consumers that want it can supply their own `ExceptionHandler`.

#### Example problem+json response

Request: `POST /users` with body `{ "email": "not-an-email", "age": 17 }` against a schema requiring `email` to match `format: email` and `age >= 18`.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "string does not match format 'email'",
  "pointer": "/email",
  "keyword": "format"
}
```

For a missing required header `X-Request-Id`:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "required header is missing",
  "pointer": "/headers/x-request-id",
  "keyword": "required"
}
```

## Server wiring & body capture

```java
new OpenApiServer(spec, jsonMapper, handlers, exceptionHandler);          // port 8080
new OpenApiServer(spec, jsonMapper, handlers, exceptionHandler, port);
```

Internal init creates one `HttpContext` at `spec.basePath()` with the filter chain `ExceptionFilter` → `RequestPreparationFilter`, and installs a catch-all 404 context at `/`. Virtual-thread-per-task executor unchanged.

`RequestPreparationFilter` stores these exchange attributes for downstream code:

| attribute        | type                  | source                                   |
|------------------|-----------------------|------------------------------------------|
| `body`           | `byte[]`              | raw request body                         |
| `parsed-body`    | `Object`              | result of `JsonMapper.mapFrom(body)`     |
| `operation-id`   | `String`              | matched operation's id                   |
| `path-parameters`| `Map<String,String>`  | extracted by `PathTemplate.match`        |

A static helper `com.retailsvc.http.Request` provides typed accessors:

```java
public final class Request {
  public static byte[] bytes(HttpExchange e);
  public static Object parsed(HttpExchange e);
  public static String operationId(HttpExchange e);
  public static Map<String,String> pathParams(HttpExchange e);
  private Request() {}
}
```

`BodyHandler.RequestBodyWrapper` (the 130-line `HttpExchange` decorator) is removed.

## Public API surface (consumer-visible)

```java
package com.retailsvc.http;

public final class OpenApiServer implements AutoCloseable { /* see "Server wiring" */ }

@FunctionalInterface public interface JsonMapper {
  Object mapFrom(byte[] body);
}
@FunctionalInterface public interface ExceptionHandler {
  void handle(HttpExchange exchange, Throwable t) throws IOException;
}

public final class Handlers {
  public static ExceptionHandler defaultExceptionHandler();
  public static HttpHandler notFoundHandler();
}

public final class Request { /* attribute accessors */ }

public final class ValidationException extends RuntimeException { public ValidationError error(); }
public final class NotFoundException extends RuntimeException {}
public final class MethodNotAllowedException extends RuntimeException { public Set<HttpMethod> allowed(); }
public final class MissingOperationHandlerException extends RuntimeException {}
```

Plus the `spec`, `spec.schema`, and `validate` packages listed earlier. Everything in `internal` is package-private.

## Consumer migration example

```java
// before (master)
Gson gson = new Gson();
OpenApi spec = parseSpecification("openapi.json", s -> gson.fromJson(s, OpenApi.class));
JsonMapper mapper = new JsonMapper() {
  @Override public <T> T mapFrom(byte[] body) {
    if (body.length > 0 && body[0] == '[')
      return (T) gson.fromJson(new String(body), List.class);
    return (T) gson.fromJson(new String(body), Map.class);
  }
};
new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());

// after (refactor)
Gson gson = new Gson();
Map<String,Object> raw = gson.fromJson(Files.readString(Path.of("openapi.json")), Map.class);
Spec spec = Spec.from(raw);
JsonMapper mapper = body -> gson.fromJson(new String(body), Object.class);
new OpenApiServer(spec, mapper, handlers, Handlers.defaultExceptionHandler());
```

YAML: replace `gson.fromJson(...)` with `new Yaml().load(text)` (returns `Map<String,Object>`); the rest is identical. The `fix/support-yaml` branch's `Function<Object,String> toJson` callback is no longer needed.

## What's in this refactor

Folded in opportunistically because the typed records make them one-liners:

- `minLength` / `maxLength`
- `minItems` / `maxItems`
- `uniqueItems`
- `multipleOf`
- `exclusiveMinimum` / `exclusiveMaximum` (3.1 numeric form)
- `type: ["string","null"]` parsing (and legacy `nullable: true` folded into the type set)

Bug fix carried in by the new model:

- The current `Schema.minimum` defaults to `Double.MIN_VALUE` (≈ 4.9e-324, the smallest *positive* double), which silently rejects all negative numbers. The new model uses `null` to mean "unspecified" and treats absence as no constraint.

Tests:

- Every existing test rewritten against the new API.
- New tests for the cheap-keyword additions above.
- Combinator records have parser round-trip tests; validator tests assert `UnsupportedOperationException` until the relevant follow-up spec lands.

Build:

- Bump `pom.xml` `<release>` from 21 to 25.
- Bump `.java-version` from 21 to 25.
- The `Dockerfile` base image moves from `eclipse-temurin:21-jre-alpine` to `eclipse-temurin:25-jre-alpine`.
- The `setup-java` step in `.github/workflows/commit.yaml` is unchanged (it reads `.java-version`); the resolver picks 25 automatically.
- No new runtime dependencies. SLF4J stays `provided`.

## Documentation updates

The following docs reference Java 21 today and must be updated as part of the refactor:

- **`README.md`** — "Prerequisites: Java SDK 21 or later" → 25. Code examples in the README are unaffected (they're library-API-level, not Java-version-level), but they will need to be rewritten to match the new public API (`Spec.from(Map<String,Object>)`, `JsonMapper` SAM, `Request` helper, etc.).
- **`CLAUDE.md`** — current text says "Java 21 library" / "Java 21 is required (see `.java-version`)". Both update to 25. Architecture description also updates to reflect the post-refactor pipeline (`ExceptionFilter` → `RequestPreparationFilter`, sealed `Schema`, `Spec.from`, etc.).
- **No other in-tree docs reference the Java version**, but a global `grep -r "Java 21\|java-21\|release>21<\|version 21"` is part of the implementation plan's verification step.

## OpenAPI 3.1 gap inventory (follow-up specs)

Each item below becomes its own small spec/PR after the refactor lands. Listed in the recommended order.

> **Reprioritized 2026-05-08.** Wave 1 and the high-impact slices of the original Wave 2 (string formats, numeric widths) have landed. The remaining items have been re-ordered to prioritize parameter/body fidelity, refs/topology, and extensions — the keywords most real-world specs depend on — over niche JSON Schema validation keywords. Item numbering follows the original inventory in parentheses so historical references still resolve.

**Wave 1 — high impact, low cost, unblocked by typed model — ✅ DONE**

1. `requestBody.required: true` enforcement when body is empty.
2. `additionalProperties: false` and `additionalProperties: { schema }` enforcement.
3. Combinators: `oneOf` / `anyOf` / `allOf` (one spec — they share machinery).
4. `not` + `const` + top-level `enum` + schema booleans (`true` / `false`).

**Wave 2 (original, partially done — string formats and numeric widths)**

5. ✅ Format expansion: `email`, `uri`, `uri-reference`, `hostname`, `ipv4`, `ipv6`, `regex`, `byte` (base64), `binary`, `password`.
8. ✅ Format-driven width validation: `int32` / `int64` overflow, `float` / `double` precision.

Items 6 and 7 from the original Wave 2 are demoted to the new Wave 4 below.

**Wave 2 — parameter & request-body fidelity + extensions (new)**

- (orig #9) Parameter `style` + `explode` for `query`, `path`, `header`, `cookie`.
- (orig #10) Array query parameters (`?ids=1&ids=2` and `?ids=1,2`).
- (orig #11) `deepObject` query style.
- (orig #12) `content` instead of `schema` on parameters.
- (orig #13) `cookie` parameter location.
- (orig #14) Media-type ranges (`application/*`, charset suffix tolerance).
- (orig #15) Non-JSON request bodies: `application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain`. `encoding` object on multipart fields.
- (orig #29) Extensions (`x-*` keys) — at minimum: silently preserved, accessible via raw map fallback. Pulled forward from old Wave 6 because consumers rely on vendor extensions across the spec.

**Wave 3 — refs & spec topology (new)**

- (orig #19) Cross-document `$ref`.
- (orig #20) JSON Schema `$defs` (3.1 alternative to `components.schemas`).
- (orig #21) `$dynamicRef` / `$dynamicAnchor` (rarely used).
- (orig #22) Multiple `servers[]` entries; server variables (`{tenant}.example.com`).
- (orig #23) Path-level parameters (defined on `PathItem`, shared across operations).
- (orig #24) Path-level / operation-level `servers`.

**Wave 4 — niche JSON Schema keywords (demoted)**

- (orig #6) Object: `patternProperties`, `dependentRequired`, `dependentSchemas`, `propertyNames`.
- (orig #7) Array: `contains` / `minContains` / `maxContains`, `prefixItems` (tuple validation).

**Wave 5 — responses**

- (orig #16) Validate response body against `responses[status].content[mediaType].schema`.
- (orig #17) Validate response headers.
- (orig #18) `default` response handling.

**Wave 6 — UX & metadata**

- (orig #25) Multi-error collection (gather all failures per request, not first-only).
- (orig #26) `discriminator` (used with combinators).
- (orig #27) `readOnly` / `writeOnly` (skip validation in the wrong direction).
- (orig #28) `deprecated` (warning logging on use).

**Wave 7 — last**

- (orig #30) Security: `securitySchemes` parsing; `security` enforcement at operation level.

## Out of scope for this design

- Server-side route specificity ordering (current first-match-wins is preserved).
- Trie-based router optimization.
- Pluggable validator (single `Validator` interface is enough; `DefaultValidator` is the only implementation shipped).
- Response writing helpers / serialization (consumer's responsibility, unchanged).
- Streaming / chunked request bodies (current full-buffer behavior preserved; opt-in streaming is a future spec).

## Sequencing assumption

The `fix/support-yaml` branch lands on master before this refactor begins. The refactor builds on that merged state and removes the YAML-specific `toJson` callback as part of replacing `SpecificationLoader` with `Spec.from(Map<String,Object>)`.
