package com.retailsvc.http.openapi.model;

import com.sun.net.httpserver.HttpExchange;

public interface GetRequestBody {

  default byte[] getRequestBody(HttpExchange exchange) {
    return (byte[]) exchange.getAttribute("body");
  }
}
