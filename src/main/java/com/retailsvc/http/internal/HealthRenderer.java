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

  /** Codepoints below this value are control characters and must be unicode-escaped in JSON. */
  private static final int FIRST_PRINTABLE_ASCII = 0x20;

  private HealthRenderer() {}

  public static String toJson(HealthOutcome outcome) {
    StringBuilder out = new StringBuilder(INITIAL_BUFFER_CAPACITY);
    out.append('{');
    appendStringField(out, "outcome", outcome.outcome());
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
      appendStringField(out, "id", d.id());
      out.append(',');
      appendStringField(out, "status", d.status());
      out.append('}');
    }
  }

  private static void appendStringField(StringBuilder out, String name, String value) {
    out.append('"').append(name).append("\":\"");
    appendEscaped(out, value);
    out.append('"');
  }

  private static void appendEscaped(StringBuilder out, String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> appendUnicodeOrLiteral(out, c);
      }
    }
  }

  private static void appendUnicodeOrLiteral(StringBuilder out, char c) {
    if (c < FIRST_PRINTABLE_ASCII) {
      out.append(String.format("\\u%04x", (int) c));
    } else {
      out.append(c);
    }
  }
}
