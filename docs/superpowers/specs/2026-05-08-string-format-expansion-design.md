# String format expansion (Wave 2 item 5)

**Status:** design approved 2026-05-08
**Source inventory:** `docs/superpowers/specs/2026-05-07-openapi-refactor-design.md` §9, Wave 2 item 5

## Goal

Extend `DefaultValidator` to recognize 10 additional `format` values defined by OpenAPI 3.1 / JSON Schema 2020-12 on `StringSchema`:

`email`, `uri`, `uri-reference`, `hostname`, `ipv4`, `ipv6`, `regex`, `byte`, `binary`, `password`.

These join the three already supported (`uuid`, `date`, `date-time`).

## Non-goals

- Numeric format-width validation (`int32`, `int64`, `float`, `double`) — Wave 2 item 8, separate spec/PR.
- Consumer-defined custom formats / `FormatValidator` SPI — deferred; non-breaking to add later.
- Toggling the JSON Schema 2020-12 `format-assertion` vocabulary on/off — we always assert, matching current behavior.
- Changes to `StringSchema` record shape or `Spec` parsing.

## Decisions

- **Closed set.** Only the 13 well-known formats (current 3 + the 10 below) are recognized. Unknown `format` values continue to be silently ignored. (User decision, 2026-05-08.)
- **Always assert.** Consistent with current behavior of `uuid` / `date` / `date-time`.
- **Syntactic-only network checks.** No DNS lookups for `hostname`, `ipv4`, `ipv6`, `uri`. Avoid `InetAddress.getByName`.
- **No new dependencies.** Java stdlib + regex only.

## Per-format strategy

| Format | Strategy |
|---|---|
| `email` | Regex `^[^\s@]+@[^\s@]+\.[^\s@]+$`. Pragmatic; matches what most JSON Schema validators do in practice. Full RFC 5322 grammar is not worth the complexity. |
| `uri` | `URI.create(str)` succeeds *and* `isAbsolute()` is true. |
| `uri-reference` | `URI.create(str)` succeeds. |
| `hostname` | Regex per RFC 1123: labels 1–63 chars, alphanumeric + hyphens, hyphens not at label boundaries, total length ≤ 253. |
| `ipv4` | Regex `^((25[0-5]\|2[0-4]\d\|1?\d?\d)\.){3}(25[0-5]\|2[0-4]\d\|1?\d?\d)$`. Strict dotted-quad. |
| `ipv6` | The standard JSON Schema 2020-12 IPv6 regex: 8 hex groups with `::` compression and optional embedded IPv4 trailer. Single explicit regex, not the `URI("http://[…]/")` hack — avoids surprises around zone IDs and mapped forms. |
| `regex` | `Pattern.compile(str)`, catch `PatternSyntaxException`. |
| `byte` | `Base64.getDecoder().decode(str)` (strict, not MIME), catch `IllegalArgumentException`. |
| `binary` | No-op (always passes). Not meaningful as a JSON string format. |
| `password` | No-op. UI hint per OAS. |

## Code organization

Current state: `DefaultValidator.validateStringFormat` is a `switch` with a `default` that ignores unknown formats. Adding 10 more arms makes the method noisy and a bad fit for `switch`.

Refactor in this PR:

- Introduce a private static registry inside `DefaultValidator`:
  ```java
  private record FormatCheck(Predicate<String> isValid, String message) {}
  private static final Map<String, FormatCheck> FORMAT_CHECKS = Map.ofEntries(...);
  ```
- `validateStringFormat` becomes a single map lookup; missing key → ignore (preserves current "unknown format ignored" behavior).
- Pre-compiled `Pattern` constants live as `private static final` fields next to the registry.
- No-op formats (`binary`, `password`) are entries with `s -> true`. Keeping them in the map (rather than as omissions falling through to the ignore branch) documents that they're recognized-and-intentionally-permissive, not unknown.

Error rendering is unchanged: `fail(pointer, "format", message, value)` produces the same RFC 7807 400 response shape as today.

## Tests

Plan to put new tests next to existing format tests. Before writing, check whether a `StringFormatValidationTest` (or similar) already exists; extend it if so, otherwise create one.

For each newly added format:

- ≥ 1 valid example (passes validation).
- ≥ 2 invalid examples covering distinct failure modes (e.g., `ipv4`: out-of-range octet *and* wrong group count).

Integration coverage: at least two formats wired through `OpenApiServer` end-to-end in an `*IT.java` to confirm a 400 with the `application/problem+json` body shape currently produced for `uuid`/`date`/`date-time`. One should be a regex-based format and one should be a parsing-based format (e.g., `email` + `byte`).

No-op formats (`binary`, `password`) get a single test each: any string passes, including obviously non-binary / non-password content, to lock in the no-op semantics.

Test fixtures: `src/test/resources/openapi.json` and the parallel `openapi.yaml` will gain a small operation that exercises one of the new formats end-to-end (the IT case). Per project rule, both files must mirror each other.

## Acceptance criteria

- All 10 formats recognized; valid inputs pass, invalid inputs produce a 400 with `format` in the violation pointer and a human-readable message.
- `uuid`, `date`, `date-time` behavior is byte-for-byte unchanged.
- Unknown `format` values are still silently ignored.
- No new runtime dependencies.
- `mvn verify` passes; coverage for the new branches reflected in the JaCoCo report.
