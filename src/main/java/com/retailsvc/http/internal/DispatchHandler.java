package com.retailsvc.http.internal;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.RequestInterceptor;
import com.retailsvc.http.Response;
import com.retailsvc.http.ResponseDecorator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {

  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();
  public static final String RESPONSE_ATTR = "com.retailsvc.http.response";

  private final Map<String, RequestHandler> handlers;
  private final List<RequestInterceptor> interceptors;
  private final List<ResponseDecorator> decorators;
  private final ResponseRenderer renderer;
  private final StreamingLimit streamingLimit;

  public DispatchHandler(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators,
      ResponseRenderer renderer) {
    this(handlers, interceptors, decorators, renderer, StreamingLimit.UNLIMITED);
  }

  /**
   * As the 4-argument constructor, with {@code streamingLimit} capping how many streaming requests
   * reach their handler at once. The slot is taken here, after validation and security, so a client
   * that is slow to send its body, or isn't authenticated, never holds one.
   */
  public DispatchHandler(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators,
      ResponseRenderer renderer,
      StreamingLimit streamingLimit) {
    this.handlers = Map.copyOf(handlers);
    this.interceptors = List.copyOf(interceptors);
    this.decorators = List.copyOf(decorators);
    this.renderer = renderer;
    this.streamingLimit = streamingLimit;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    if (!request.isStreaming()) {
      dispatch(exchange, request);
      return;
    }
    if (!streamingLimit.tryAcquire()) {
      Response rejection = streamingLimit.rejection();
      exchange.setAttribute(RESPONSE_ATTR, rejection);
      renderer.render(exchange, rejection);
      return;
    }
    try {
      dispatch(exchange, request);
    } finally {
      streamingLimit.release();
    }
  }

  private void dispatch(HttpExchange exchange, Request request) throws IOException {
    RequestHandler handler = handlers.get(request.operationId());
    Response response = invoke(0, request, handler);
    exchange.setAttribute(RESPONSE_ATTR, response);
    renderer.render(exchange, response);
  }

  private Response invoke(int idx, Request request, RequestHandler handler) {
    if (idx == interceptors.size()) {
      Response response = handler.handle(request);
      for (ResponseDecorator decorator : decorators) {
        response = decorator.decorate(request, response);
      }
      return response;
    }
    return interceptors.get(idx).around(request, () -> invoke(idx + 1, request, handler));
  }
}
