package com.retailsvc.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Decorate the `{@link HttpExchange} with the 'body' attribute, holding the request body as a
 * byte-array.
 *
 * @author thced
 */
public class BodyHandler extends Filter {

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    if (bytes.length > 0) {
      exchange.setAttribute("body", bytes);
    }
    chain.doFilter(exchange);
  }

  @Override
  public String description() {
    return "Body handler";
  }
}
