package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.internal.DispatchHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestTest {

  @Test
  void readsBoundContext() throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    Request req =
        new Request(
            exchange,
            new byte[] {1, 2, 3},
            Map.of("k", "v"),
            "get-x",
            Map.of("id", "42"),
            Map.of(),
            List.of());

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
  void exposesQueryParams() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI())
        .thenReturn(URI.create("http://h/x?name=Alice%20Smith&active=true&active=false"));
    Request req = new Request(exchange, new byte[0], null, "op", Map.of(), Map.of(), List.of());

    assertThat(req.rawQuery()).isEqualTo("name=Alice%20Smith&active=true&active=false");
    assertThat(req.queryParam("name")).isEqualTo("Alice Smith");
    assertThat(req.queryParam("active")).isEqualTo("true");
    assertThat(req.queryParam("missing")).isNull();
    assertThat(req.queryParams())
        .containsEntry("name", "Alice Smith")
        .containsEntry("active", "true");
  }

  @Test
  void queryParamsEmptyWhenNoQuery() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestURI()).thenReturn(URI.create("http://h/x"));
    Request req = new Request(exchange, new byte[0], null, "op", Map.of(), Map.of(), List.of());

    assertThat(req.rawQuery()).isNull();
    assertThat(req.queryParams()).isEmpty();
    assertThat(req.queryParam("anything")).isNull();
  }

  @Test
  void respondAfterTerminalThrows() throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    Request req = new Request(exchange, new byte[0], null, "op", Map.of(), Map.of(), List.of());

    req.respond(204).empty();

    assertThatThrownBy(() -> req.respond(200))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already sent");
  }
}
