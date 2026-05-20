package com.retailsvc.http.internal;

import com.retailsvc.http.Dependency;
import com.retailsvc.http.HealthOutcome;
import java.util.List;

/**
 * Hand-rolled JSON renderer for {@link HealthOutcome} responses.
 *
 * <p>Mirrors {@link ProblemDetailRenderer} — the library avoids pulling in a JSON writer for a
 * handful of fixed fields with known shapes.
 */
public final class HealthRenderer {

  /** Initial capacity sized for a typical health document with a handful of dependencies. */
  private static final int INITIAL_BUFFER_CAPACITY = 128;

  private HealthRenderer() {}

  public static String toJson(HealthOutcome outcome) {
    StringBuilder out = new StringBuilder(INITIAL_BUFFER_CAPACITY);
    out.append('{');
    JsonStrings.appendStringField(out, "outcome", outcome.outcome());
    out.append(",\"dependencies\":[");
    appendDependencies(out, outcome.dependencies());
    out.append("]}");
    return out.toString();
  }

  private static void appendDependencies(StringBuilder out, List<Dependency> deps) {
    for (int i = 0; i < deps.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      Dependency d = deps.get(i);
      out.append('{');
      JsonStrings.appendStringField(out, "id", d.id());
      out.append(',');
      JsonStrings.appendStringField(out, "status", d.status());
      out.append('}');
    }
  }
}
