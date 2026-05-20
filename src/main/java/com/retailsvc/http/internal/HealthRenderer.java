package com.retailsvc.http.internal;

import com.retailsvc.http.Dependency;
import java.util.List;

/**
 * Built-in JSON writer for the health endpoint wire shape. Keeps {@code Handlers} free of
 * serialisation logic and removes the need for a JSON library on the classpath.
 */
public final class HealthRenderer {

  /** Initial StringBuilder capacity sized for an outcome with one dependency (the common case). */
  private static final int INITIAL_CAPACITY = 64;

  private HealthRenderer() {}

  public static String renderJson(boolean up, List<Dependency> dependencies) {
    StringBuilder sb = new StringBuilder(INITIAL_CAPACITY);
    sb.append("{\"outcome\":\"").append(label(up)).append("\",\"dependencies\":[");
    for (int i = 0; i < dependencies.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      Dependency d = dependencies.get(i);
      sb.append("{\"id\":");
      JsonStrings.appendQuoted(sb, d.id());
      sb.append(",\"status\":\"").append(label(d.up())).append("\"}");
    }
    return sb.append("]}").toString();
  }

  private static String label(boolean up) {
    return up ? "Up" : "Down";
  }
}
