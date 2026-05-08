package com.retailsvc.http.spec;

import java.util.Locale;

public enum HttpMethod {
  GET,
  POST,
  PUT,
  DELETE,
  PATCH,
  HEAD,
  OPTIONS,
  TRACE,
  CONNECT;

  public static HttpMethod parse(String s) {
    return HttpMethod.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
