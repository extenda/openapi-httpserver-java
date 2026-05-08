package com.retailsvc.http.internal;

import com.retailsvc.http.validate.ValidationError;

/**
 * Renders a {@link ValidationError} as an RFC 7807 {@code application/problem+json} document.
 *
 * <p>Hand-rolled to avoid pulling in a JSON library; only six fixed fields are emitted, all with
 * known shapes, so a writer-from-scratch is safer than a generic encoder.
 */
public final class ProblemDetailRenderer {

  private static final String PROBLEM_TYPE = "about:blank";
  private static final String PROBLEM_TITLE = "Bad Request";
  private static final int PROBLEM_STATUS = 400;

  /** Initial capacity of the JSON buffer; sized for a typical problem-detail document. */
  private static final int INITIAL_BUFFER_CAPACITY = 128;

  /** Codepoints below this value are control characters and must be unicode-escaped in JSON. */
  private static final int FIRST_PRINTABLE_ASCII = 0x20;

  private ProblemDetailRenderer() {}

  public static String render(ValidationError error) {
    StringBuilder out = new StringBuilder(INITIAL_BUFFER_CAPACITY);
    out.append('{');
    appendStringField(out, "type", PROBLEM_TYPE);
    out.append(',');
    appendStringField(out, "title", PROBLEM_TITLE);
    out.append(',');
    appendIntField(out, "status", PROBLEM_STATUS);
    out.append(',');
    appendStringField(out, "detail", error.message());
    out.append(',');
    appendStringField(out, "pointer", error.pointer());
    out.append(',');
    appendStringField(out, "keyword", error.keyword());
    out.append('}');
    return out.toString();
  }

  private static void appendStringField(StringBuilder out, String name, String value) {
    out.append('"').append(name).append("\":\"");
    appendEscaped(out, value);
    out.append('"');
  }

  private static void appendIntField(StringBuilder out, String name, int value) {
    out.append('"').append(name).append("\":").append(value);
  }

  /**
   * Appends {@code value} to {@code out} with JSON-string escaping applied. Handles the six
   * mandatory escape sequences and emits {@code &#92;uXXXX} for control characters below {@link
   * #FIRST_PRINTABLE_ASCII}.
   */
  private static void appendEscaped(StringBuilder out, String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
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
