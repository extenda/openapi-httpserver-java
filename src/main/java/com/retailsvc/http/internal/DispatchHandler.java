package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {

  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

  private final Map<String, RequestHandler> handlers;

  public DispatchHandler(Map<String, RequestHandler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler h = handlers.get(request.operationId());
    if (h == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    h.handle(request);
  }
}
