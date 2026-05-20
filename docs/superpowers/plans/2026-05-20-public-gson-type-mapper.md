# Public GsonTypeMapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a public, caller-configurable `GsonTypeMapper` in `com.retailsvc.http` that mirrors the Jackson mappers and exposes a `gsonBuilder()` method for extending the library default.

**Architecture:** Refactor the existing internal `GsonJsonMapper` so its no-arg constructor builds a JSR-310-aware `Gson` and a new `Gson`-accepting constructor stores any user-supplied instance. Add a new public final `GsonTypeMapper` in `com.retailsvc.http` that composes a `GsonJsonMapper` delegate and adds a `gsonBuilder()` accessor returning the wrapped Gson's `newBuilder()`. Auto-registration in `OpenApiServer` is unchanged.

**Tech Stack:** Java 25, Gson, JUnit 5, AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-05-20-public-gson-type-mapper-design.md`

---

## File Structure

- Modify: `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java` — add `GsonJsonMapper(Gson)` ctor, extract `defaultGson()`, add `gson()` accessor.
- Create: `src/main/java/com/retailsvc/http/GsonTypeMapper.java` — public `TypedTypeMapper` wrapper exposing `gsonBuilder()`.
- Create: `src/test/java/com/retailsvc/http/GsonTypeMapperTest.java` — public-API tests.
- Modify: `README.md` — add Gson example to the JSON mapping section.

---

### Task 1: Refactor internal `GsonJsonMapper` to accept a `Gson`

**Files:**
- Modify: `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java`

- [ ] **Step 1: Add a failing test for the `Gson`-accepting constructor**

Append to `src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java` (keep existing tests; add new ones inside the class, before the closing brace):

```java
  @Test
  void constructorWithGsonUsesSuppliedInstance() {
    com.google.gson.Gson custom =
        new com.google.gson.GsonBuilder().serializeNulls().create();
    GsonJsonMapper m = new GsonJsonMapper(custom);

    assertThat(new String(m.writeTo(java.util.Collections.singletonMap("k", null)),
            java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("{\"k\":null}");
  }

  @Test
  void gsonAccessorReturnsWrappedInstance() {
    com.google.gson.Gson custom = new com.google.gson.Gson();
    GsonJsonMapper m = new GsonJsonMapper(custom);

    assertThat(m.gson()).isSameAs(custom);
  }
```

Note: inline FQNs above are intentional only to keep the test patch small — clean them up in Step 3 by adding proper imports.

- [ ] **Step 2: Run the tests; expect compilation failure**

Run: `mvn -q test -Dtest=GsonJsonMapperTest`
Expected: compilation error — `GsonJsonMapper(Gson)` and `gson()` do not exist.

- [ ] **Step 3: Implement the refactor**

Replace the constructor block in `src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java` (currently lines 47–62) with:

```java
  private final Gson gson;

  public GsonJsonMapper() {
    this(defaultGson());
  }

  public GsonJsonMapper(Gson gson) {
    this.gson = java.util.Objects.requireNonNull(gson, "gson must not be null");
  }

  /** Returns the wrapped {@link Gson} instance. */
  public Gson gson() {
    return gson;
  }

  private static Gson defaultGson() {
    return new GsonBuilder()
        .registerTypeAdapter(Instant.class, iso(Instant::toString, Instant::parse))
        .registerTypeAdapter(
            OffsetDateTime.class, iso(OffsetDateTime::toString, OffsetDateTime::parse))
        .registerTypeAdapter(
            ZonedDateTime.class, iso(ZonedDateTime::toString, ZonedDateTime::parse))
        .registerTypeAdapter(
            LocalDateTime.class, iso(LocalDateTime::toString, LocalDateTime::parse))
        .registerTypeAdapter(LocalDate.class, iso(LocalDate::toString, LocalDate::parse))
        .registerTypeAdapter(LocalTime.class, iso(LocalTime::toString, LocalTime::parse))
        .create();
  }
```

Then add `import java.util.Objects;` at the top, remove the inline `java.util.Objects.requireNonNull` qualifier (use bare `Objects.requireNonNull`), and clean up the test file by adding proper imports for `Gson`, `GsonBuilder`, `Collections`, `StandardCharsets` instead of leaving inline FQNs (per project rule "no inline fully-qualified type names").

- [ ] **Step 4: Run the tests; expect pass**

Run: `mvn -q test -Dtest=GsonJsonMapperTest`
Expected: all tests pass, including the two new ones plus the 12 existing ones.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/retailsvc/http/internal/gson/GsonJsonMapper.java \
        src/test/java/com/retailsvc/http/internal/gson/GsonJsonMapperTest.java
SKIP=commitlint git commit -m "refactor: Allow GsonJsonMapper to accept a Gson instance"
```

---

### Task 2: Add public `GsonTypeMapper`

**Files:**
- Create: `src/main/java/com/retailsvc/http/GsonTypeMapper.java`
- Create: `src/test/java/com/retailsvc/http/GsonTypeMapperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/retailsvc/http/GsonTypeMapperTest.java`:

```java
package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GsonTypeMapperTest {

  @Test
  void noArgConstructorRoundTripsInstantAsIso8601() {
    GsonTypeMapper mapper = new GsonTypeMapper();

    byte[] out = mapper.writeTo(Map.of("ts", Instant.parse("2026-05-13T10:00:00Z")));

    assertThat(new String(out, StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00:00Z\"}");
  }

  @Test
  void readAsDelegatesToWrappedGson() {
    GsonTypeMapper mapper = new GsonTypeMapper();

    Item item =
        mapper.readAs(
            "{\"id\":\"x\",\"qty\":3}".getBytes(StandardCharsets.UTF_8),
            "application/json",
            Item.class);

    assertThat(item.id).isEqualTo("x");
    assertThat(item.qty).isEqualTo(3);
  }

  @Test
  void customGsonIsUsed() {
    Gson custom = new GsonBuilder().serializeNulls().create();
    GsonTypeMapper mapper = new GsonTypeMapper(custom);

    assertThat(new String(mapper.writeTo(Collections.singletonMap("k", null)), StandardCharsets.UTF_8))
        .isEqualTo("{\"k\":null}");
  }

  @Test
  void nullGsonRejected() {
    assertThatNullPointerException().isThrownBy(() -> new GsonTypeMapper(null));
  }

  @Test
  void gsonBuilderReturnsBuilderForWrappedGson() {
    Gson custom = new GsonBuilder().serializeNulls().create();
    GsonTypeMapper mapper = new GsonTypeMapper(custom);

    Gson derived = mapper.gsonBuilder().create();

    assertThat(derived.toJson(Collections.singletonMap("k", null))).isEqualTo("{\"k\":null}");
  }

  static final class Item {
    String id;
    int qty;
  }
}
```

- [ ] **Step 2: Run the tests; expect compilation failure**

Run: `mvn -q test -Dtest=GsonTypeMapperTest`
Expected: compilation error — class `GsonTypeMapper` does not exist.

- [ ] **Step 3: Create the implementation**

Create `src/main/java/com/retailsvc/http/GsonTypeMapper.java`:

```java
package com.retailsvc.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retailsvc.http.internal.gson.GsonJsonMapper;
import java.util.Objects;

/**
 * {@link TypeMapper} for {@code application/json} backed by Gson. Mirrors the ergonomics of
 * {@link Jackson2JsonTypeMapper} / {@link Jackson3JsonTypeMapper}: the caller supplies a fully
 * configured {@link Gson}; this class never silently mutates it.
 *
 * <p>The no-argument constructor uses the library's default {@link Gson} — the same JSR-310-aware
 * instance the built-in auto-registration produces — making this a drop-in replacement for the
 * auto-registered mapper when callers want to wire it explicitly.
 *
 * <p>To extend the library default with extra type adapters or settings, use {@link #gsonBuilder()}:
 *
 * <pre>{@code
 * Gson custom =
 *     new GsonTypeMapper()
 *         .gsonBuilder()
 *         .registerTypeAdapter(MyType.class, new MyTypeAdapter())
 *         .create();
 * new GsonTypeMapper(custom);
 * }</pre>
 */
public final class GsonTypeMapper implements TypedTypeMapper {

  private final GsonJsonMapper delegate;

  /** Creates a mapper backed by the library's default JSR-310-aware {@link Gson}. */
  public GsonTypeMapper() {
    this.delegate = new GsonJsonMapper();
  }

  /**
   * Creates a mapper backed by the supplied {@link Gson}.
   *
   * @throws NullPointerException if {@code gson} is null
   */
  public GsonTypeMapper(Gson gson) {
    this.delegate = new GsonJsonMapper(Objects.requireNonNull(gson, "gson must not be null"));
  }

  /**
   * Returns a {@link GsonBuilder} pre-populated with the wrapped {@link Gson}'s configuration, so
   * callers can derive a customized {@link Gson} from the library default (or from their own
   * starting point).
   */
  public GsonBuilder gsonBuilder() {
    return delegate.gson().newBuilder();
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return delegate.readFrom(body, contentTypeHeader);
  }

  @Override
  public <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type) {
    return delegate.readAs(body, contentTypeHeader, type);
  }

  @Override
  public byte[] writeTo(Object value) {
    return delegate.writeTo(value);
  }
}
```

- [ ] **Step 4: Run the tests; expect pass**

Run: `mvn -q test -Dtest=GsonTypeMapperTest`
Expected: 5 tests pass.

- [ ] **Step 5: Run the full unit test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS; no regressions.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/retailsvc/http/GsonTypeMapper.java \
        src/test/java/com/retailsvc/http/GsonTypeMapperTest.java
SKIP=commitlint git commit -m "feat: Add public GsonTypeMapper exposing gsonBuilder()"
```

---

### Task 3: Document `GsonTypeMapper` in README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Inspect current Gson section**

Run: `grep -n "GsonJsonMapper\|## JSON" README.md`
Read the JSON-mapping section (around the lines reported) to confirm the existing structure and pick the best insertion point — right after the existing "Gson is the default JSON serializer" passage, alongside the Jackson examples.

- [ ] **Step 2: Add Gson example**

After the existing description of the auto-registered `GsonJsonMapper`, add a subsection covering explicit wiring:

````markdown
### Explicit Gson wiring

When you want to customize Gson, wire `GsonTypeMapper` explicitly. The no-arg
form uses the same JSR-310-aware default as auto-registration; pass a `Gson`
to fully control serialization:

```java
OpenApiServer.builder()
    .spec(spec)
    .bodyMapper("application/json", new GsonTypeMapper(myGson))
    .handlers(handlers)
    .build();
```

To extend the library default (instead of building a `Gson` from scratch),
unwrap it via `gsonBuilder()`:

```java
Gson custom =
    new GsonTypeMapper()
        .gsonBuilder()
        .registerTypeAdapter(Money.class, new MoneyAdapter())
        .create();

OpenApiServer.builder()
    .spec(spec)
    .bodyMapper("application/json", new GsonTypeMapper(custom))
    .handlers(handlers)
    .build();
```
````

Place this block after the existing `GsonJsonMapper` paragraph (around `README.md:130`) and before the next top-level section.

- [ ] **Step 3: Commit**

```bash
git add README.md
SKIP=commitlint git commit -m "docs: Document public GsonTypeMapper and gsonBuilder()"
```

---

### Task 4: Full verification

**Files:** none modified.

- [ ] **Step 1: Run full test + integration suite**

Run: `mvn -q verify`
Expected: BUILD SUCCESS; both Surefire and Failsafe green.

- [ ] **Step 2: Confirm auto-registration path still works**

Run: `mvn -q test -Dtest=TypeMapperRegistrationTest`
Expected: PASS — confirms `OpenApiServer` still auto-registers `GsonJsonMapper` unchanged.

- [ ] **Step 3: Push branch (Sonar runs in CI)**

SonarLint MCP cannot see files inside `.claude/worktrees/` (its `/workspace` mount is the main repo). Sonar findings for this branch will only surface after push via the CI scan — review the CI report after Step 4 and address any findings in a follow-up commit.

- [ ] **Step 4: Push branch**

```bash
git push -u origin feat/gson-type-mapper
```

The user will open the PR manually (gh token cannot create PRs in this repo).
