package com.retailsvc.http.spec;

import java.util.Locale;

/** HTTP request methods supported by OpenAPI path operations. */
public enum HttpMethod {
  /** HTTP {@code GET} method. */
  GET,
  /** HTTP {@code POST} method. */
  POST,
  /** HTTP {@code PUT} method. */
  PUT,
  /** HTTP {@code DELETE} method. */
  DELETE,
  /** HTTP {@code PATCH} method. */
  PATCH,
  /** HTTP {@code HEAD} method. */
  HEAD,
  /** HTTP {@code OPTIONS} method. */
  OPTIONS,
  /** HTTP {@code TRACE} method. */
  TRACE,
  /** HTTP {@code CONNECT} method. */
  CONNECT;

  /**
   * Parses the given string into an {@link HttpMethod}, case-insensitively.
   *
   * @param s the method name
   * @return the matching {@link HttpMethod}
   */
  public static HttpMethod parse(String s) {
    return HttpMethod.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
