package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.RequestInterceptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {

  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

  private final Map<String, RequestHandler> handlers;
  private final List<RequestInterceptor> interceptors;

  public DispatchHandler(
      Map<String, RequestHandler> handlers, List<RequestInterceptor> interceptors) {
    this.handlers = Map.copyOf(handlers);
    this.interceptors = List.copyOf(interceptors);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler h = handlers.get(request.operationId());
    if (h == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    invoke(0, request, h);
  }

  private void invoke(int idx, Request request, RequestHandler handler) throws IOException {
    if (idx == interceptors.size()) {
      handler.handle(request);
      return;
    }
    interceptors.get(idx).around(request, () -> invoke(idx + 1, request, handler));
  }
}
