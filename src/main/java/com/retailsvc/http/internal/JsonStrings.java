package com.retailsvc.http.internal;

/**
 * Minimal JSON string-escape helper shared by the library's hand-rolled JSON writers ({@link
 * HealthRenderer}, {@link ProblemDetailRenderer}). Lets those renderers emit RFC 8259 compliant
 * strings without pulling in a JSON library and without record-accessor reflection that GraalVM
 * Native Image would otherwise need configured.
 */
final class JsonStrings {

  /** Codepoints below this value are control characters and must be unicode-escaped. */
  private static final int FIRST_PRINTABLE_ASCII = 0x20;

  private JsonStrings() {}

  /** Appends {@code value} surrounded by double quotes, with JSON string-escaping applied. */
  static void appendQuoted(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < FIRST_PRINTABLE_ASCII) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
