package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Response;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

public final class ExceptionFilter extends Filter {

  private final ExceptionHandler handler;
  private final ResponseRenderer renderer;

  public ExceptionFilter(ExceptionHandler handler, ResponseRenderer renderer) {
    this.handler = handler;
    this.renderer = renderer;
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (RuntimeException | IOException t) {
      Response response = handler.handle(t);
      renderer.render(exchange, response);
    }
  }

  @Override
  public String description() {
    return "Exception filter";
  }
}
