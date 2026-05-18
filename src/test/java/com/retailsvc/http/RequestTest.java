package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsvc.http.internal.DispatchHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class RequestTest {

  private static final UnaryOperator<String> NO_HEADERS = name -> null;

  private static UnaryOperator<String> headers(String... pairs) {
    Map<String, String> map = new java.util.HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i].toLowerCase(), pairs[i + 1]);
    }
    return name -> map.get(name.toLowerCase());
  }

  @Test
  void readsBoundContext() throws Exception {
    Request req =
        new Request(
            new byte[] {1, 2, 3},
            Map.of("k", "v"),
            null,
            "get-x",
            Map.of("id", "42"),
            null,
            NO_HEADERS);

    AtomicReference<byte[]> seenBytes = new AtomicReference<>();
    AtomicReference<Object> seenParsed = new AtomicReference<>();
    AtomicReference<String> seenOpId = new AtomicReference<>();
    AtomicReference<Map<String, String>> seenPathParams = new AtomicReference<>();

    ScopedValue.where(DispatchHandler.CURRENT, req)
        .call(
            () -> {
              Request r = DispatchHandler.CURRENT.get();
              seenBytes.set(r.bytes());
              seenParsed.set(r.parsed());
              seenOpId.set(r.operationId());
              seenPathParams.set(r.pathParams());
              return null;
            });

    assertThat(seenBytes.get()).containsExactly(1, 2, 3);
    assertThat(seenParsed.get()).isEqualTo(Map.of("k", "v"));
    assertThat(seenOpId.get()).isEqualTo("get-x");
    assertThat(seenPathParams.get()).containsEntry("id", "42");
  }

  @Test
  void asPojoDeserialisesViaTypedMapper() {
    JacksonJsonTypeMapper mapper = new JacksonJsonTypeMapper(new ObjectMapper());
    byte[] body = "{\"id\":\"x-1\",\"qty\":7}".getBytes(StandardCharsets.UTF_8);
    Request req =
        new Request(
            body,
            Map.of("id", "x-1", "qty", 7),
            mapper,
            "op",
            Map.of(),
            null,
            headers("Content-Type", "application/json"));

    Item item = req.asPojo(Item.class);

    assertThat(item.id).isEqualTo("x-1");
    assertThat(item.qty).isEqualTo(7);
  }

  @Test
  void asPojoFastPathWhenParsedAlreadyMatchesType() {
    Map<String, Object> alreadyParsed = Map.of("k", "v");
    Request req =
        new Request("x".getBytes(), alreadyParsed, null, "op", Map.of(), null, NO_HEADERS);

    Map<?, ?> result = req.asPojo(Map.class);
    assertThat(result).isSameAs(alreadyParsed);
  }

  @Test
  void asPojoThrowsWhenBodyEmpty() {
    Request req = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);

    assertThatThrownBy(() -> req.asPojo(Item.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no body");
  }

  @Test
  void asPojoThrowsWhenMapperNotTyped() {
    TypeMapper plain =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] b, String h) {
            return new String(b, StandardCharsets.UTF_8);
          }

          @Override
          public byte[] writeTo(Object v) {
            return v.toString().getBytes(StandardCharsets.UTF_8);
          }
        };
    Request req =
        new Request(
            "hello".getBytes(),
            "hello",
            plain,
            "op",
            Map.of(),
            null,
            headers("Content-Type", "text/plain"));

    assertThatThrownBy(() -> req.asPojo(Item.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TypedTypeMapper");
  }

  static final class Item {
    public String id;
    public int qty;
  }

  @Test
  void pathParamReturnsValueOrNull() {
    Request req = new Request(new byte[0], null, null, "op", Map.of("id", "42"), null, NO_HEADERS);

    assertThat(req.pathParam("id")).isEqualTo("42");
    assertThat(req.pathParam("missing")).isNull();
  }

  @Test
  void exposesQueryParams() {
    Request req =
        new Request(
            new byte[0],
            null,
            null,
            "op",
            Map.of(),
            "name=Alice%20Smith&active=true&active=false",
            NO_HEADERS);

    assertThat(req.rawQuery()).isEqualTo("name=Alice%20Smith&active=true&active=false");
    assertThat(req.queryParam("name")).contains("Alice Smith");
    assertThat(req.queryParam("active")).contains("true");
    assertThat(req.queryParam("missing")).isEmpty();
    assertThat(req.queryParams())
        .containsEntry("name", "Alice Smith")
        .containsEntry("active", "true");
  }

  @Test
  void queryParamsEmptyWhenNoQuery() {
    Request req = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);

    assertThat(req.rawQuery()).isNull();
    assertThat(req.queryParams()).isEmpty();
    assertThat(req.queryParam("anything")).isEmpty();
  }

  @Test
  void queryParamBlankIsTreatedAsAbsent() {
    Request req =
        new Request(new byte[0], null, null, "op", Map.of(), "limit=&offset=%20", NO_HEADERS);

    assertThat(req.queryParam("limit")).isEmpty();
    assertThat(req.queryParam("offset")).isEmpty();
  }

  @Test
  void contentTypeShortcutsContentTypeHeader() {
    Request req =
        new Request(
            new byte[0],
            null,
            null,
            "op",
            Map.of(),
            null,
            headers("Content-Type", "application/json"));

    assertThat(req.contentType()).contains("application/json");
  }

  @Test
  void contentTypeEmptyWhenHeaderAbsent() {
    Request req = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);

    assertThat(req.contentType()).isEmpty();
  }

  @Test
  void principalsDefaultsEmpty() {
    Request r = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);

    assertThat(r.principals()).isEmpty();
    assertThat(r.principal("anything")).isEmpty();
  }

  @Test
  void withPrincipalsCreatesImmutableCopy() {
    Request r = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);
    Map<String, Object> principals = Map.of("bearerAuth", "user-123");
    Request copy = r.withPrincipals(principals);

    assertThat(copy).isNotSameAs(r);
    assertThat(r.principals()).isEmpty();
    assertThat(copy.principals()).isEqualTo(principals);
    assertThat(copy.principal("bearerAuth")).contains("user-123");
  }

  @Test
  void withPrincipalsDoesNotShareUnderlyingMap() {
    Request r = new Request(new byte[0], null, null, "op", Map.of(), null, NO_HEADERS);
    HashMap<String, Object> mutable = new HashMap<>();
    mutable.put("a", "b");
    Request copy = r.withPrincipals(mutable);
    mutable.put("a", "MUTATED");

    assertThat(copy.principal("a")).contains("b");
  }

  @Test
  void headerReturnsOptionalAndBlankIsAbsent() {
    Request req =
        new Request(
            new byte[0],
            null,
            null,
            "op",
            Map.of(),
            null,
            headers("X-Trace", "abc", "X-Empty", "   "));

    assertThat(req.header("X-Trace")).contains("abc");
    assertThat(req.header("X-Empty")).isEmpty();
    assertThat(req.header("Missing")).isEmpty();
  }
}
