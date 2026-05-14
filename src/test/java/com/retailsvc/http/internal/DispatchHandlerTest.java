package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DispatchHandlerTest {

  private static HttpExchange stubExchange() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    return exchange;
  }

  private static DispatchHandler dispatcher(Map<String, RequestHandler> handlers) {
    return new DispatchHandler(handlers, List.of(), List.of(), new ResponseRenderer(Map.of()));
  }

  private static void withRequest(
      HttpExchange exchange, String operationId, ScopedValue.CallableOp<Void, Exception> body)
      throws Exception {
    Request req = new Request(exchange, new byte[0], null, operationId, Map.of());
    ScopedValue.where(DispatchHandler.CURRENT, req).call(body);
  }

  @Test
  void invokesRegisteredHandler() throws Exception {
    AtomicBoolean called = new AtomicBoolean(false);
    RequestHandler handler =
        req -> {
          called.set(true);
          return Response.status(HTTP_OK);
        };
    HttpExchange ex = stubExchange();

    withRequest(
        ex,
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", handler)).handle(ex);
          return null;
        });

    assertThat(called.get()).isTrue();
  }

  @Test
  void throwsWhenHandlerMissing() {
    DispatchHandler d = dispatcher(Map.of());
    HttpExchange ex = stubExchange();

    assertThatThrownBy(
            () ->
                withRequest(
                    ex,
                    "ghost",
                    () -> {
                      d.handle(ex);
                      return null;
                    }))
        .isInstanceOf(MissingOperationHandlerException.class);
  }
}
