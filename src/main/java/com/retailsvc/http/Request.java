package com.retailsvc.http;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

public final class Request {
  public static final String BODY = "body";
  public static final String PARSED_BODY = "parsed-body";
  public static final String OPERATION_ID = "operation-id";
  public static final String PATH_PARAMETERS = "path-parameters";

  private Request() {}

  public static byte[] bytes(HttpExchange e) {
    return (byte[]) e.getAttribute(BODY);
  }

  public static Object parsed(HttpExchange e) {
    return e.getAttribute(PARSED_BODY);
  }

  public static String operationId(HttpExchange e) {
    return (String) e.getAttribute(OPERATION_ID);
  }

  @SuppressWarnings("unchecked")
  public static Map<String, String> pathParams(HttpExchange e) {
    return (Map<String, String>) e.getAttribute(PATH_PARAMETERS);
  }
}
