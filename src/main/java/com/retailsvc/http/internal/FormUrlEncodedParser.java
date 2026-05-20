package com.retailsvc.http.internal;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.validate.ValidationError;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses an {@code application/x-www-form-urlencoded} request body. */
public final class FormUrlEncodedParser {

  /** Creates a new parser. */
  public FormUrlEncodedParser() {
    // Stateless; nothing to initialise.
  }

  /**
   * Parses the body to a {@code Map<String, Object>} ({@code String} or {@code List<String>}).
   *
   * @param body raw request body bytes
   * @param contentTypeHeader the request {@code Content-Type} header (used for the charset
   *     parameter); may be {@code null}
   * @return decoded form fields preserving insertion order
   */
  public Map<String, Object> parse(byte[] body, String contentTypeHeader) {
    Charset charset = resolveCharset(contentTypeHeader);
    if (body.length == 0) {
      return new LinkedHashMap<>();
    }
    String text = new String(body, charset);
    Map<String, Object> out = new LinkedHashMap<>();
    for (String pair : text.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq < 0 ? pair : pair.substring(0, eq);
      String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
      String key = decodeComponent(rawKey, charset);
      String value = decodeComponent(rawValue, charset);
      addEntry(out, key, value);
    }
    return out;
  }

  private static String decodeComponent(String value, Charset charset) {
    try {
      return URLDecoder.decode(value, charset);
    } catch (IllegalArgumentException ex) {
      ValidationException ve =
          new ValidationException(
              new ValidationError(
                  "/body", "decode", "malformed form URL encoding: " + ex.getMessage(), value));
      ve.initCause(ex);
      throw ve;
    }
  }

  private static void addEntry(Map<String, Object> out, String key, String value) {
    out.merge(
        key,
        value,
        (existing, incoming) -> {
          if (existing instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> typed = (List<String>) list;
            typed.add((String) incoming);
            return typed;
          }
          List<String> merged = new ArrayList<>();
          merged.add((String) existing);
          merged.add((String) incoming);
          return merged;
        });
  }

  private static Charset resolveCharset(String header) {
    return ContentTypeHeader.parameter(header, "charset")
        .map(FormUrlEncodedParser::safeCharset)
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
