package com.retailsvc.http.internal;

import java.util.Locale;
import java.util.Optional;

/** Parses {@code Content-Type} header values. */
public final class ContentTypeHeader {

  private ContentTypeHeader() {}

  /**
   * Returns the bare media type, stripping parameters and lower-casing for case-insensitive
   * matching (RFC 9110 / 2045). {@code null} → {@code application/json}.
   *
   * @param header raw {@code Content-Type} header value (nullable; missing header is treated as
   *     {@code application/json}).
   * @return the bare media type lower-cased with parameters stripped.
   */
  public static String mediaType(String header) {
    if (header == null) {
      return "application/json";
    }
    int semi = header.indexOf(';');
    String bare = (semi < 0 ? header : header.substring(0, semi));
    return bare.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns the named parameter value (e.g. {@code charset}), or empty if absent.
   *
   * @param header raw {@code Content-Type} header value (nullable returns empty).
   * @param name the parameter name to look up (case-insensitive, e.g. {@code charset}).
   * @return the parameter value (unquoted if quoted), or empty if the header has no such parameter.
   */
  public static Optional<String> parameter(String header, String name) {
    if (header == null) {
      return Optional.empty();
    }
    String target = name.toLowerCase(Locale.ROOT);
    int semi = header.indexOf(';');
    if (semi < 0) {
      return Optional.empty();
    }
    for (String p : header.substring(semi + 1).split(";")) {
      String trimmed = p.trim();
      int eq = trimmed.indexOf('=');
      String key = (eq <= 0) ? "" : trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (key.equals(target)) {
        return Optional.of(unquote(trimmed.substring(eq + 1).trim()));
      }
    }
    return Optional.empty();
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
