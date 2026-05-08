package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DispatchHandlerTest {
  private final Map<String, Object> attrs = new HashMap<>();

  private HttpExchange exchange(String operationId) {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getAttribute(Request.OPERATION_ID)).thenReturn(operationId);
    return ex;
  }

  @Test
  void invokesRegisteredHandler() throws Exception {
    HttpHandler handler = Mockito.mock(HttpHandler.class);
    new DispatchHandler(Map.of("get-x", handler)).handle(exchange("get-x"));
    Mockito.verify(handler).handle(Mockito.any());
  }

  @Test
  void throwsWhenHandlerMissing() {
    DispatchHandler d = new DispatchHandler(Map.of());
    assertThatThrownBy(() -> d.handle(exchange("ghost")))
        .isInstanceOf(MissingOperationHandlerException.class);
  }
}
