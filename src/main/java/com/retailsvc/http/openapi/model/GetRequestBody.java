package com.retailsvc.http.openapi.model;

import com.retailsvc.http.BodyHandler.RequestBodyWrapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Handlers requiring access to the request body of the request should implement this interface. */
public interface GetRequestBody {

  default byte[] getRequestBody(HttpExchange exchange) throws IOException {
    if (exchange instanceof RequestBodyWrapper wrapper) {
      return wrapper.getRequestBodyAsBytes();
    }
    return exchange.getRequestBody().readAllBytes();
  }
}
