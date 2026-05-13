package com.retailsvc.http.internal;

import java.util.Locale;
import java.util.Optional;

/** Parses {@code Content-Type} header values. */
public final class ContentTypeHeader {

  private ContentTypeHeader() {}

  /** Returns the bare media type, stripping parameters. {@code null} → {@code application/json}. */
  public static String subtype(String header) {
    if (header == null) {
      return "application/json";
    }
    int semi = header.indexOf(';');
    String bare = (semi < 0 ? header : header.substring(0, semi));
    return bare.trim();
  }

  /** Returns the named parameter value (e.g. {@code charset}), or empty if absent. */
  public static Optional<String> parameter(String header, String name) {
    if (header == null) {
      return Optional.empty();
    }
    String target = name.toLowerCase(Locale.ROOT);
    int semi = header.indexOf(';');
    if (semi < 0) {
      return Optional.empty();
    }
    String[] parts = header.substring(semi + 1).split(";");
    for (String p : parts) {
      String trimmed = p.trim();
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      if (!key.equals(target)) {
        continue;
      }
      String value = trimmed.substring(eq + 1).trim();
      if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      return Optional.of(value);
    }
    return Optional.empty();
  }
}
