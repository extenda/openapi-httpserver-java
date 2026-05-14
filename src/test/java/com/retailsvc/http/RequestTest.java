package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsvc.http.internal.DispatchHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestTest {

  @Test
  void readsBoundContext() throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    Request req =
        new Request(
            exchange, new byte[] {1, 2, 3}, Map.of("k", "v"), null, "get-x", Map.of("id", "42"));

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
    HttpExchange exchange = mock(HttpExchange.class);
    Headers headers = new Headers();
    headers.add("Content-Type", "application/json");
    when(exchange.getRequestHeaders()).thenReturn(headers);
    JacksonJsonTypeMapper mapper = new JacksonJsonTypeMapper(new ObjectMapper());
    byte[] body = "{\"id\":\"x-1\",\"qty\":7}".getBytes(StandardCharsets.UTF_8);
    Request req =
        new Request(exchange, body, Map.of("id", "x-1", "qty", 7), mapper, "op", Map.of());

    Item item = req.asPojo(Item.class);

    assertThat(item.id).isEqualTo("x-1");
    assertThat(item.qty).isEqualTo(7);
  }

  @Test
  void asPojoFastPathWhenParsedAlreadyMatchesType() {
    HttpExchange exchange = mock(HttpExchange.class);
    Map<String, Object> alreadyParsed = Map.of("k", "v");
    Request req = new Request(exchange, "x".getBytes(), alreadyParsed, null, "op", Map.of());

    Map<?, ?> result = req.asPojo(Map.class);
    assertThat(result).isSameAs(alreadyParsed);
  }

  @Test
  void asPojoThrowsWhenBodyEmpty() {
    HttpExchange exchange = mock(HttpExchange.class);
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of());

    assertThatThrownBy(() -> req.asPojo(Item.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no body");
  }

  @Test
  void asPojoThrowsWhenMapperNotTyped() {
    HttpExchange exchange = mock(HttpExchange.class);
    Headers headers = new Headers();
    headers.add("Content-Type", "text/plain");
    when(exchange.getRequestHeaders()).thenReturn(headers);
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
    Request req = new Request(exchange, "hello".getBytes(), "hello", plain, "op", Map.of());

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
    HttpExchange exchange = mock(HttpExchange.class);
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of("id", "42"));

    assertThat(req.pathParam("id")).isEqualTo("42");
    assertThat(req.pathParam("missing")).isNull();
  }

  @Test
  void exposesQueryParams() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI())
        .thenReturn(URI.create("http://h/x?name=Alice%20Smith&active=true&active=false"));
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of());

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
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI()).thenReturn(URI.create("http://h/x"));
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of());

    assertThat(req.rawQuery()).isNull();
    assertThat(req.queryParams()).isEmpty();
    assertThat(req.queryParam("anything")).isEmpty();
  }

  @Test
  void queryParamBlankIsTreatedAsAbsent() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI()).thenReturn(URI.create("http://h/x?limit=&offset=%20"));
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of());

    assertThat(req.queryParam("limit")).isEmpty();
    assertThat(req.queryParam("offset")).isEmpty();
  }

  @Test
  void headerReturnsOptionalAndBlankIsAbsent() {
    HttpExchange exchange = mock(HttpExchange.class);
    com.sun.net.httpserver.Headers h = new com.sun.net.httpserver.Headers();
    h.add("X-Trace", "abc");
    h.add("X-Empty", "   ");
    when(exchange.getRequestHeaders()).thenReturn(h);
    Request req = new Request(exchange, new byte[0], null, null, "op", Map.of());

    assertThat(req.header("X-Trace")).contains("abc");
    assertThat(req.header("X-Empty")).isEmpty();
    assertThat(req.header("Missing")).isEmpty();
  }
}
