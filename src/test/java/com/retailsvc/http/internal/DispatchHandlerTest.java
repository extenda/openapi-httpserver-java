package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DispatchHandlerTest {

  private static void withOperationId(
      String operationId, ScopedValue.CallableOp<Void, Exception> body) throws Exception {
    RequestContext ctx = new RequestContext(new byte[0], null, operationId, Map.of());
    ScopedValue.where(Request.CONTEXT, ctx).call(body);
  }

  @Test
  void invokesRegisteredHandler() throws Exception {
    HttpHandler handler = Mockito.mock(HttpHandler.class);
    HttpExchange ex = Mockito.mock(HttpExchange.class);

    withOperationId(
        "get-x",
        () -> {
          new DispatchHandler(Map.of("get-x", handler)).handle(ex);
          return null;
        });
    // bound op-id is "get-x"; DispatchHandler should look up the registered HttpHandler.

    Mockito.verify(handler).handle(Mockito.any());
  }

  @Test
  void throwsWhenHandlerMissing() {
    DispatchHandler d = new DispatchHandler(Map.of());
    HttpExchange ex = Mockito.mock(HttpExchange.class);

    assertThatThrownBy(
            () ->
                withOperationId(
                    "ghost",
                    () -> {
                      d.handle(ex);
                      return null;
                    }))
        .isInstanceOf(MissingOperationHandlerException.class);
  }
}
