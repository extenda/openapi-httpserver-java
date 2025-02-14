package com.retailsvc.http;

import static com.retailsvc.http.Handlers.notFoundHandler;

import com.retailsvc.http.openapi.exceptions.BadRequestTypeException;
import com.retailsvc.http.openapi.exceptions.NotFoundTypeException;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catches and delegates exceptions to the registered {@link ExceptionHandler}.
 *
 * @author thced
 */
class ExceptionHandlingFilter extends Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ExceptionHandlingFilter.class);

  private final ExceptionHandler exceptionHandler;

  public ExceptionHandlingFilter(ExceptionHandler exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
    LOG.debug("Instantiating ExceptionHandlingFilter...");
  }

  @Override
  public String description() {
    return "Exception handling filter";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try {
      chain.doFilter(exchange);
    } catch (Exception e) {
      handleException(exchange, e);
    }
  }

  private void handleException(HttpExchange exchange, Exception e) throws IOException {
    switch (e) {
      case NotFoundTypeException nf -> notFoundHandler().handle(exchange);
      case BadRequestTypeException br -> exceptionHandler.handleException(exchange, e);
      default -> exceptionHandler.handleException(exchange, e);
    }
  }
}
