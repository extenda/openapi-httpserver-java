package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.RequestInterceptor;
import com.retailsvc.http.Response;
import com.retailsvc.http.ResponseDecorator;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DispatchHandlerTest {

  private static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();

  private static HttpExchange stubExchange() {
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getResponseHeaders()).thenReturn(new Headers());
    Map<String, Object> attrs = new HashMap<>();
    doAnswer(
            inv -> {
              attrs.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(exchange)
        .setAttribute(anyString(), any());
    when(exchange.getAttribute(anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
    return exchange;
  }

  private static DispatchHandler dispatcher(Map<String, RequestHandler> handlers) {
    return new DispatchHandler(handlers, List.of(), List.of(), new ResponseRenderer(Map.of()));
  }

  private static DispatchHandler dispatcher(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators) {
    return new DispatchHandler(handlers, interceptors, decorators, new ResponseRenderer(Map.of()));
  }

  private static void withRequest(String operationId, ScopedValue.CallableOp<Void, Exception> body)
      throws Exception {
    Request req = new Request(new byte[0], null, null, operationId, Map.of(), null, n -> null);
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
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", handler)).handle(ex);
          return null;
        });

    assertThat(called.get()).isTrue();
  }

  @Test
  void decoratorSeesInterceptorBoundScopedValue() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    RequestInterceptor bindCid =
        (request, next) -> ScopedValue.where(CORRELATION_ID, "cid-123").call(next::proceed);
    ResponseDecorator stampCid =
        (req, resp) -> resp.withHeader("X-Correlation-Id", CORRELATION_ID.get());

    HttpExchange ex = stubExchange();
    AtomicReference<Response> rendered = new AtomicReference<>();

    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(bindCid), List.of(stampCid)).handle(ex);
          rendered.set((Response) ex.getAttribute(DispatchHandler.RESPONSE_ATTR));
          return null;
        });

    assertThat(rendered.get().headers()).containsEntry("X-Correlation-Id", "cid-123");
  }

  @Test
  void interceptorObservesDecoratedResponse() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    AtomicReference<Response> seen = new AtomicReference<>();
    RequestInterceptor capture =
        (request, next) -> {
          Response r = next.proceed();
          seen.set(r);
          return r;
        };
    ResponseDecorator stamp = (req, resp) -> resp.withHeader("X-Stamped", "yes");

    HttpExchange ex = stubExchange();
    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(capture), List.of(stamp)).handle(ex);
          return null;
        });

    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().headers()).containsEntry("X-Stamped", "yes");
  }

  @Test
  void interceptorCanCatchDecoratorFailure() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_OK);
    AtomicBoolean caught = new AtomicBoolean(false);
    RequestInterceptor catcher =
        (request, next) -> {
          try {
            return next.proceed();
          } catch (RuntimeException e) {
            caught.set(true);
            return Response.status(HTTP_INTERNAL_ERROR);
          }
        };
    ResponseDecorator boom =
        (req, resp) -> {
          throw new IllegalStateException("boom");
        };

    HttpExchange ex = stubExchange();
    withRequest(
        "get-x",
        () -> {
          dispatcher(Map.of("get-x", ok), List.of(catcher), List.of(boom)).handle(ex);
          return null;
        });

    assertThat(caught.get()).isTrue();
    Response rendered = (Response) ex.getAttribute(DispatchHandler.RESPONSE_ATTR);
    assertThat(rendered).isNotNull();
    assertThat(rendered.status()).isEqualTo(HTTP_INTERNAL_ERROR);
  }
}
