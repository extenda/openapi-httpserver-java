package com.retailsvc.http.internal;

import com.retailsvc.http.validate.ValidationError;

public final class ProblemDetailRenderer {
  private ProblemDetailRenderer() {}

  public static String render(ValidationError error) {
    return "{"
        + "\"type\":\"about:blank\","
        + "\"title\":\"Bad Request\","
        + "\"status\":400,"
        + "\"detail\":\""
        + escape(error.message())
        + "\","
        + "\"pointer\":\""
        + escape(error.pointer())
        + "\","
        + "\"keyword\":\""
        + escape(error.keyword())
        + "\""
        + "}";
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.toString();
  }
}
