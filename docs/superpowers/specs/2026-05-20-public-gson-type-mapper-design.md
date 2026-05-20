# Public GsonTypeMapper

## Problem

`Jackson2JsonTypeMapper` and `Jackson3JsonTypeMapper` are public in
`com.retailsvc.http` and accept a fully-configured `ObjectMapper`, giving
callers direct control over JSON serialization. The Gson equivalent,
`GsonJsonMapper`, lives in `com.retailsvc.http.internal.gson`, is
package-private, has only a no-arg constructor, and hard-codes a Gson
instance with JSR-310 type adapters. Callers cannot pass their own `Gson`
or extend the library's defaults without forking the class.

## Goals

- Expose a public, caller-configurable Gson adapter in `com.retailsvc.http`
  that mirrors the Jackson mappers' ergonomics.
- Let callers extend the library's default (JSR-310-aware) Gson without
  re-implementing the type adapters.
- Preserve the current auto-registration behavior: putting Gson on the
  classpath continues to wire up the JSR-310-aware mapper with no
  configuration.

## Non-goals

- Changing the auto-registration mechanism or class-name lookup in
  `OpenApiServer`.
- Adding new JSR-310 adapters or changing the existing ISO-8601 format.
- Deprecating or removing `GsonJsonMapper`; it stays as the internal
  implementation.

## Design

### Internal refactor: `com.retailsvc.http.internal.gson.GsonJsonMapper`

- Add a constructor `GsonJsonMapper(Gson gson)` that stores the supplied
  instance.
- Existing no-arg constructor delegates to `new GsonJsonMapper(defaultGson())`
  where `defaultGson()` is a private static method building the current
  JSR-310-aware default.
- Add a `Gson gson()` accessor so the public wrapper can reach the
  underlying instance.
- `readFrom` / `readAs` / `writeTo` behavior is unchanged.

### New public class: `com.retailsvc.http.GsonTypeMapper`

`public final class GsonTypeMapper implements TypedTypeMapper`

Constructors:

- `GsonTypeMapper()` — wraps `new GsonJsonMapper()` (library default, JSR-310
  aware).
- `GsonTypeMapper(Gson gson)` — wraps `new GsonJsonMapper(gson)`; throws
  `NullPointerException` via `Objects.requireNonNull` if `gson` is null.

Methods:

- `public GsonBuilder gsonBuilder()` — returns `delegate.gson().newBuilder()`,
  a builder pre-populated with the wrapped `Gson`'s configuration. Lets
  callers extend the library default in one line:
  `new GsonTypeMapper().gsonBuilder().registerTypeAdapter(...).create()`.
- `readFrom`, `readAs`, `writeTo` — delegate to the internal mapper.

### `OpenApiServer`

No change. The auto-registration class-name constant still points at
`com.retailsvc.http.internal.gson.GsonJsonMapper` and the no-arg
constructor still produces a JSR-310-aware mapper.

### Tests

New `src/test/java/com/retailsvc/http/GsonTypeMapperTest.java`, JUnit 5
with AssertJ, camelCase method names, static imports, curly braces on
every block:

- `noArgConstructorRoundTripsJsr310` — verifies `Instant`,
  `OffsetDateTime`, `LocalDate` survive a write→read cycle as ISO-8601.
- `customGsonIsUsed` — supplies a `Gson` with a custom type adapter and
  asserts the adapter wins over the default behavior.
- `nullGsonRejected` — constructor with `null` throws `NullPointerException`.
- `gsonBuilderPreservesWrappedConfig` — builds a `GsonTypeMapper` with a
  custom Gson, calls `gsonBuilder().create()`, and asserts the custom
  adapter still applies on the new instance.

The existing `GsonJsonMapperTest` stays as-is to cover internal behavior.

### Documentation

Update README "JSON mapping" section to add a Gson example alongside the
Jackson ones, showing both `new GsonTypeMapper()` and the
`gsonBuilder()` extension pattern.

## Risks

- Two public ways to obtain a Gson-backed mapper (auto-registration vs
  explicit `new GsonTypeMapper()`). Mitigation: README clarifies that
  auto-registration is for the zero-config path and `GsonTypeMapper` is
  for callers who want to customize.
