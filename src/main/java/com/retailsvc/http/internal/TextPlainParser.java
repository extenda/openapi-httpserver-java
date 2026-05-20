package com.retailsvc.http.internal;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/** Decodes a text/plain request body using the charset declared on {@code Content-Type}. */
public final class TextPlainParser {

  /** Creates a new parser. */
  public TextPlainParser() {
    // Stateless; nothing to initialise.
  }

  /**
   * Decodes the body as text using the charset declared on {@code Content-Type} (default UTF-8).
   *
   * @param body raw request body bytes
   * @param contentTypeHeader the request {@code Content-Type} header; may be {@code null}
   * @return the decoded string
   */
  public String parse(byte[] body, String contentTypeHeader) {
    Charset charset = resolveCharset(contentTypeHeader);
    return new String(body, charset);
  }

  private static Charset resolveCharset(String header) {
    return ContentTypeHeader.parameter(header, "charset")
        .map(TextPlainParser::safeCharset)
        .orElse(StandardCharsets.UTF_8);
  }

  private static Charset safeCharset(String name) {
    try {
      return Charset.forName(name);
    } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
      return StandardCharsets.UTF_8;
    }
  }
}
