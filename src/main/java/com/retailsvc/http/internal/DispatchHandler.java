package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

public final class DispatchHandler implements HttpHandler {
  private final Map<String, HttpHandler> handlers;

  public DispatchHandler(Map<String, HttpHandler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String opId = Request.operationId();
    HttpHandler h = handlers.get(opId);
    if (h == null) {
      throw new MissingOperationHandlerException(opId);
    }
    h.handle(exchange);
  }
}
