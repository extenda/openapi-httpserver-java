package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Response;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Outermost filter in the chain. Catches {@link RuntimeException} and {@link IOException} thrown by
 * any downstream filter or handler, maps the throwable to a {@link Response} via the user-supplied
 * {@link ExceptionHandler}, and renders it via the shared {@link ResponseRenderer}.
 *
 * <p>Runs outside any interceptor {@link java.lang.ScopedValue} bindings — those are torn down as
 * the exception unwinds.
 */
public final class ExceptionFilter extends Filter {

  private final ExceptionHandler handler;
  private final ResponseRenderer renderer;

  /**
   * Creates a new exception filter.
   *
   * @param handler user-supplied exception-to-response mapper
   * @param renderer the shared response renderer used to write the error response
   */
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
