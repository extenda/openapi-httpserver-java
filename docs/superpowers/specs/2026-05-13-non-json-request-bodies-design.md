# Non-JSON request bodies (Wave 2)

**Date:** 2026-05-13
**Status:** Design — ready for implementation plan
**Wave/item:** Wave 2 (new), orig #15 — partial. Slice A only: `application/x-www-form-urlencoded` and `text/plain`. Multipart deferred.

## Problem

`RequestPreparationFilter.validateAndParseBody` calls `jsonMapper.mapFrom(body)` unconditionally, regardless of the wire `Content-Type`. Operations whose `requestBody.content` declares `application/x-www-form-urlencoded` or `text/plain` cannot be served — even when the spec is well-formed — because the JSON mapper is applied to non-JSON bytes.

## Goals

1. Accept `application/x-www-form-urlencoded` and `text/plain` request bodies when the operation's spec declares them, in addition to the existing `application/json` path.
2. Parse form bodies into a shape the existing validator can consume against an `ObjectSchema` (single values as `String`, repeated keys as `List<String>`).
3. Coerce form-field string values to the property's declared type (number / integer / boolean / arrays of those), matching how query and path parameters are already coerced at the parameter boundary.
4. Hoist the existing `coerceParameterValue` helper into a shared `internal/ValueCoercion` utility, since form-body coercion is the same logic.
5. Keep `RequestPreparationFilter` focused — extract parsing into purpose-specific classes rather than growing the filter.

## Non-goals

- `multipart/form-data` (Slice B/C — deferred to a follow-up; needs boundary parsing, per-part headers, file-upload concerns, `encoding` object handling).
- Coercion for `text/plain`. Bodies declared as `text/plain` are passed through as `String`; the schema is expected to be `type: string` (optionally with `format` / `pattern`). Non-string schemas against a `text/plain` body produce the existing strict 400.
- Multi-value semantics for form bodies beyond OpenAPI explode=true repeated-key behaviour. `style: pipeDelimited` / `spaceDelimited` are out of scope (those are Wave 2's parameter-style items).
- Changing JSON body handling. JSON bodies remain strict (no coercion), per #48 / #49.

## Design

### Wire behaviour

| Wire Content-Type                       | Internal representation                                              | Validation mode |
| --------------------------------------- | -------------------------------------------------------------------- | --------------- |
| `application/json` (existing)           | whatever `JsonMapper` returns                                        | strict, no coercion |
| `application/x-www-form-urlencoded`     | `Map<String, Object>` — `String` per field, `List<String>` if repeated | coerce per property schema |
| `text/plain`                            | `String` (UTF-8 or charset from `Content-Type`)                      | strict (`type: string` expected) |

Content-Type selection:
1. Read the `Content-Type` request header. Strip media-type parameters (`; charset=...; boundary=...`) — the subtype is what matches the spec's `requestBody.content` key.
2. Look up the spec's `MediaType` by the bare subtype string (`application/x-www-form-urlencoded`, `text/plain`, `application/json`, …).
3. If the spec does not declare that subtype → existing 400 `ValidationError` with keyword `content-type` (unchanged).
4. Empty body + `requestBody.required: true` → existing 400 (unchanged).
5. Empty body + non-required → null (unchanged).

### Form-urlencoded parser

New internal class `FormUrlEncodedParser`:

- Input: `byte[]` body, charset from `Content-Type` `charset=` parameter (default UTF-8).
- Decode bytes to `String` with the resolved charset.
- Split on `&`. For each pair:
  - If `=` is absent → `{ key → "" }`.
  - Otherwise split on the first `=`. Empty-value entries (`"key="`) → `{ key → "" }`.
- URL-decode both key and value with `URLDecoder.decode(s, charset)`. `+` is mapped to space (standard `URLDecoder` behaviour).
- Output: `Map<String, Object>` backed by `LinkedHashMap` (preserves insertion order).
  - First occurrence of a key: store the `String`.
  - Second occurrence: replace with `new ArrayList<>(List.of(prevString, newString))`.
  - Subsequent: append to the existing `List<String>`.

Coercion is applied after parsing, before validation:

- If the body schema is an `ObjectSchema`, walk the parsed map. For each entry `(key, value)`:
  - Look up the property schema by `key`. If absent → leave the entry unchanged (validation handles `additionalProperties`).
  - If the property schema is `IntegerSchema` / `NumberSchema` / `BooleanSchema` and the value is a single `String` → call `ValueCoercion.coerce(string, schema, "/" + key)`.
  - If the property schema is `ArraySchema` with primitive `items` → for each `String` element in the `List<String>`, call `ValueCoercion.coerce(...)`; replace the list contents. The pointer is `"/" + key + "/" + index`.
  - Otherwise leave the value as-is.
- Coercion failures throw `ValidationException` with the JSON-pointer set to the failing property.
- If the body schema is not an `ObjectSchema` (rare for form bodies) → no coercion; the validator decides what to do with the raw `Map`.

### Text/plain parser

New internal class `TextPlainParser`:

- Input: `byte[]` body, charset from `Content-Type` `charset=` parameter (default UTF-8).
- Output: the decoded `String`. No transformation.
- Validation runs against the declared schema as-is. The expected schema is `type: string`; anything else surfaces the existing strict type error.

### Shared helpers

- `internal/ContentTypeHeader` — static helpers:
  - `subtype(String header)` → bare media-type (`"text/plain"` from `"text/plain; charset=utf-8"`). `null` → `"application/json"` (current default).
  - `parameter(String header, String name)` → `Optional<String>` for a named parameter, with quoted values unquoted (`charset="utf-8"` → `"utf-8"`).
- `internal/ValueCoercion` — hoisted from `RequestPreparationFilter.coerceParameterValue`. Signature: `Object coerce(String raw, Schema schema, String pointer)`. Same semantics as today; no new behaviour. The filter's private method is deleted and the call site updated.

### Dispatch refactor inside `RequestPreparationFilter`

`validateAndParseBody` is refactored to:

1. Resolve the spec's `MediaType` for the request (existing logic).
2. Read the wire content type via `ContentTypeHeader.subtype(...)`.
3. Dispatch to a parser by subtype. Each parser has slightly different inputs (form needs the body schema for coercion; text needs only the charset header; JSON delegates to the user-supplied mapper), so the dispatch is a plain `switch` rather than a uniform `BodyParser` interface:

```java
Object parsed = switch (subtype) {
  case "application/x-www-form-urlencoded" -> formParser.parseAndCoerce(body, header, mt.schema());
  case "text/plain"                        -> textParser.parse(body, header);
  default                                  -> jsonMapper.mapFrom(body);  // application/json
};
validator.validate(parsed, mt.schema(), "");
return parsed;
```

- `formParser` and `textParser` are stateless instances constructed once in the filter constructor.
- The `default` branch preserves today's exact behaviour for `application/json` (and any spec-declared JSON-ish subtypes that fall through to the user `JsonMapper`).

### File layout

**Create:**
- `src/main/java/com/retailsvc/http/internal/FormUrlEncodedParser.java`
- `src/main/java/com/retailsvc/http/internal/TextPlainParser.java`
- `src/main/java/com/retailsvc/http/internal/ContentTypeHeader.java`
- `src/main/java/com/retailsvc/http/internal/ValueCoercion.java`

**Modify:**
- `src/main/java/com/retailsvc/http/internal/RequestPreparationFilter.java` — `validateAndParseBody` dispatch refactor; remove private `coerceParameterValue`; route remaining call to `ValueCoercion.coerce`.

### Error handling

- Content-Type the spec does not declare → existing `content-type` 400 (unchanged).
- Form body cannot be decoded with the requested charset → `ValidationException` with pointer `/body` and keyword `decode`. Message includes the charset.
- Form-field coercion failure → `ValidationException` with pointer `/<key>` (or `/<key>/<index>` for arrays).
- Text/plain decode failure → `ValidationException` `/body` `decode`.
- All `ValidationException`s flow through the existing 400 RFC-7807 path.

## Testing

### Unit tests

- `internal/FormUrlEncodedParserTest.java`
  - Empty body → empty map.
  - Single field → `{ a: "1" }`.
  - Repeated key (`a=1&a=2`) → `{ a: ["1", "2"] }`.
  - Empty value (`a=`) → `{ a: "" }`.
  - Key without `=` (`a`) → `{ a: "" }`.
  - Percent-decoding for both key and value (`a%20b=c%26d` → `{ "a b": "c&d" }`).
  - Plus-as-space (`a=b+c` → `{ a: "b c" }`).
  - Charset from header (`charset=iso-8859-1`) — decode a non-UTF-8 byte sequence and assert string equality.
  - Coercion: integer / number / boolean fields parsed per object schema property types.
  - Coercion: array property with integer items → `List<Long>`.
  - Coercion failure: `"x=abc"` against `type: integer` → `ValidationException` at pointer `/x`.

- `internal/TextPlainParserTest.java`
  - UTF-8 body decoded round-trip.
  - Explicit charset from header.
  - Empty body → empty string.

- `internal/ContentTypeHeaderTest.java`
  - `subtype("application/json")` → `"application/json"`.
  - `subtype("text/plain; charset=utf-8")` → `"text/plain"`.
  - `subtype(null)` → `"application/json"`.
  - `parameter("text/plain; charset=iso-8859-1", "charset")` → `Optional.of("iso-8859-1")`.
  - Quoted parameter value (`charset="utf-8"`) → unquoted.
  - Missing parameter → `Optional.empty()`.
  - Parameter name match is case-insensitive (`CHARSET=utf-8` → found).

- `internal/ValueCoercionTest.java`
  - Hoisted helper covered directly: integer, number, boolean, default (string) happy paths.
  - Failure cases (`"abc"` → integer/number → throws with `type` keyword and pointer).

### Integration tests

`src/test/java/com/retailsvc/http/NonJsonBodyIT.java`:

- POST `application/x-www-form-urlencoded` with `name=foo&age=30` against a new spec operation whose body schema is `{ type: object, properties: { name: { type: string }, age: { type: integer } } }` → handler receives `Map` with `name="foo"` and `age=30L`.
- POST same operation with repeated key for an array property (`tags=a&tags=b`) → handler sees `List` of strings (or coerced types per items schema).
- POST with `age=abc` → 400 RFC-7807 with `/age` pointer and `type` keyword.
- POST `text/plain` body `"hello"` against schema `{ type: string }` → handler receives `"hello"`.
- POST `text/plain` body against schema `{ type: integer }` → 400 (regression check on strict text/plain).
- Spec declaring only `application/json`, wire sends `application/x-www-form-urlencoded` → existing "unsupported content type" 400 (regression).

### Test fixtures

- Extend `src/test/resources/openapi.json` with at least two new operations:
  - `POST /form-echo` — accepts `application/x-www-form-urlencoded` with an object schema (string + integer + array-of-string properties).
  - `POST /text-echo` — accepts `text/plain` with a `type: string` schema.
- Mirror the additions in `src/test/resources/openapi.yaml` (the two fixtures must describe the same API per the yaml-mirrors-json convention).
- Add two handler classes under `src/test/java/com/retailsvc/http/start/` to echo the parsed body, used by `NonJsonBodyIT`.

## Documentation

`README.md`:

- Add a short subsection under the existing Usage section documenting that form-urlencoded and text/plain bodies are supported when declared in the spec, that form fields are coerced to property types, and that text/plain bodies are kept as `String`.
- Note that JSON bodies remain strict (no coercion).

## Out of scope

- `multipart/form-data` and OpenAPI `encoding`. Tracked as Slice B/C of orig #15.
- New body parsers exposed as public API. The new classes live in `internal/`; callers do not need to wire anything beyond their existing `JsonMapper`.
- Streaming form/text parsing. Bodies are read into a `byte[]` as today.
