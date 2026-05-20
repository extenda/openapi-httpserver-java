package com.retailsvc.http.internal;

/**
 * Small JSON string-writing helpers shared by the library's hand-rolled renderers.
 *
 * <p>Used in place of a full JSON writer because the library only ever emits a handful of fixed
 * fields with known shapes. Package-private — not part of the public API.
 */
final class JsonStrings {

  /** Codepoints below this value are control characters and must be unicode-escaped in JSON. */
  private static final int FIRST_PRINTABLE_ASCII = 0x20;

  private JsonStrings() {}

  /** Appends {@code "name":"<escaped value>"} to {@code out}. */
  static void appendStringField(StringBuilder out, String name, String value) {
    out.append('"').append(name).append("\":\"");
    appendEscaped(out, value);
    out.append('"');
  }

  /**
   * Appends {@code value} with JSON-string escaping applied. Handles the seven mandatory escape
   * sequences ({@code \\}, {@code \"}, {@code \n}, {@code \r}, {@code \t}, {@code \b}, {@code \f})
   * and emits a {@code &#92;uXXXX} sequence for any remaining control character below {@link
   * #FIRST_PRINTABLE_ASCII}.
   */
  static void appendEscaped(StringBuilder out, String value) {
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
