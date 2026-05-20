package com.retailsvc.http.internal;

import java.nio.charset.StandardCharsets;

/**
 * Built-in JSON writer for the {@code application/problem+json} (RFC 7807) wire shape. Keeps the
 * exception and security paths free of any {@code TypeMapper}, so the library can emit problem
 * responses without a JSON library on the classpath (and without record-accessor reflection that
 * GraalVM Native Image would otherwise need configured).
 *
 * <p>Null-valued fields are omitted, matching the default Gson encoding the library historically
 * produced.
 */
public final class ProblemDetailRenderer {

  /** Initial capacity sized for a typical problem-detail document. */
  private static final int INITIAL_CAPACITY = 128;

  private ProblemDetailRenderer() {}

  public static byte[] renderJson(ProblemDetail pd) {
    StringBuilder out = new StringBuilder(INITIAL_CAPACITY);
    out.append('{');
    boolean first = true;
    first = appendString(out, first, "type", pd.type());
    first = appendString(out, first, "title", pd.title());
    first = appendInt(out, first, "status", pd.status());
    first = appendString(out, first, "detail", pd.detail());
    first = appendString(out, first, "pointer", pd.pointer());
    appendString(out, first, "keyword", pd.keyword());
    out.append('}');
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static boolean appendString(StringBuilder out, boolean first, String name, String value) {
    if (value == null) {
      return first;
    }
    if (!first) {
      out.append(',');
    }
    out.append('"').append(name).append("\":");
    JsonStrings.appendQuoted(out, value);
    return false;
  }

  private static boolean appendInt(StringBuilder out, boolean first, String name, int value) {
    if (!first) {
      out.append(',');
    }
    out.append('"').append(name).append("\":").append(value);
    return false;
  }
}
