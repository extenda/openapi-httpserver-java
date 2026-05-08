package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class ExceptionFilter extends Filter {
  private final ExceptionHandler handler;

  public ExceptionFilter(ExceptionHandler handler) {
    this.handler = handler;
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      handler.handle(exchange, t);
    }
  }

  @Override
  public String description() {
    return "Exception filter";
  }
}
