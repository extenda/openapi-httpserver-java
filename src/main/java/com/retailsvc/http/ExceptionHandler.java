package com.retailsvc.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Handle exceptions thrown from a {@link HttpHandler}.
 *
 * @author thced
 */
public interface ExceptionHandler {

  void handleException(HttpExchange exchange, Exception e) throws IOException;
}
