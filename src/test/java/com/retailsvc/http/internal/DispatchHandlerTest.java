package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DispatchHandlerTest {

  private static void withRequest(String operationId, ScopedValue.CallableOp<Void, Exception> body)
      throws Exception {
    HttpExchange exchange = mock(HttpExchange.class);
    Request req = new Request(exchange, new byte[0], null, operationId, Map.of(), Map.of());
    ScopedValue.where(DispatchHandler.CURRENT, req).call(body);
  }

  @Test
  void invokesRegisteredHandler() throws Exception {
    AtomicBoolean called = new AtomicBoolean(false);
    RequestHandler handler = req -> called.set(true);
    HttpExchange ex = mock(HttpExchange.class);

    withRequest(
        "get-x",
        () -> {
          new DispatchHandler(Map.of("get-x", handler)).handle(ex);
          return null;
        });

    assertThat(called.get()).isTrue();
  }

  @Test
  void throwsWhenHandlerMissing() {
    DispatchHandler d = new DispatchHandler(Map.of());
    HttpExchange ex = mock(HttpExchange.class);

    assertThatThrownBy(
            () ->
                withRequest(
                    "ghost",
                    () -> {
                      d.handle(ex);
                      return null;
                    }))
        .isInstanceOf(MissingOperationHandlerException.class);
  }
}
