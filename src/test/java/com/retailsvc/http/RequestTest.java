package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.internal.DispatchHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
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
            Map.of());

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
  void respondAfterTerminalThrows() throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    Request req = new Request(exchange, new byte[0], null, "op", Map.of(), Map.of());

    req.respond(204).empty();

    assertThatThrownBy(() -> req.respond(200))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already sent");
  }
}
