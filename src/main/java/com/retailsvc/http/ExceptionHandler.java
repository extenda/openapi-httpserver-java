package com.retailsvc.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Handle exceptions thrown from a {@link HttpHandler}.
 *
 * @author thced
 */
@FunctionalInterface
public interface ExceptionHandler {

  void handle(HttpExchange exchange, Throwable t) throws IOException;
}
