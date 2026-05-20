package com.retailsvc.http.internal;

import com.retailsvc.http.Dependency;
import java.util.List;

/**
 * Built-in JSON writer for the health endpoint wire shape. Keeps {@code Handlers} free of
 * serialisation logic and removes the need for a JSON library on the classpath.
 */
public final class HealthRenderer {

  private HealthRenderer() {}

  public static String renderJson(boolean up, List<Dependency> dependencies) {
    StringBuilder sb = new StringBuilder(64);
    sb.append("{\"outcome\":\"").append(label(up)).append("\",\"dependencies\":[");
    for (int i = 0; i < dependencies.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      Dependency d = dependencies.get(i);
      sb.append("{\"id\":");
      appendJsonString(sb, d.id());
      sb.append(",\"status\":\"").append(label(d.up())).append("\"}");
    }
    return sb.append("]}").toString();
  }

  private static void appendJsonString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static String label(boolean up) {
    return up ? "Up" : "Down";
  }
}
