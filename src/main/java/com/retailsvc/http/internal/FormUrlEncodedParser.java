package com.retailsvc.http.internal;

import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.Schema;
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

  /** Parses the body to a {@code Map<String, Object>} ({@code String} or {@code List<String>}). */
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
      throw new ValidationException("/body", "decode", "Malformed form URL encoding", ex);
    }
  }

  private static void addEntry(Map<String, Object> out, String key, String value) {
    Object existing = out.get(key);
    if (existing == null) {
      out.put(key, value);
      return;
    }
    if (existing instanceof List<?> list) {
      @SuppressWarnings("unchecked")
      List<String> typed = (List<String>) list;
      typed.add(value);
      return;
    }
    List<String> list = new ArrayList<>();
    list.add((String) existing);
    list.add(value);
    out.put(key, list);
  }

  /** Returns the parsed map after coercing field values against the given body schema. */
  public Map<String, Object> parseAndCoerce(byte[] body, String contentTypeHeader, Schema schema) {
    Map<String, Object> parsed = parse(body, contentTypeHeader);
    if (!(schema instanceof ObjectSchema obj)) {
      return parsed;
    }
    Map<String, Schema> properties = obj.properties();
    for (Map.Entry<String, Object> e : parsed.entrySet()) {
      Schema propSchema = properties.get(e.getKey());
      if (propSchema == null) {
        continue;
      }
      String pointer = "/" + e.getKey();
      Object value = e.getValue();
      if (propSchema instanceof ArraySchema arr && value instanceof List<?> list) {
        List<Object> coerced = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          coerced.add(ValueCoercion.coerce((String) list.get(i), arr.items(), pointer + "/" + i));
        }
        e.setValue(coerced);
      } else if (propSchema instanceof ArraySchema arr && value instanceof String s) {
        e.setValue(List.of(ValueCoercion.coerce(s, arr.items(), pointer + "/0")));
      } else if (value instanceof String s) {
        e.setValue(ValueCoercion.coerce(s, propSchema, pointer));
      }
    }
    return parsed;
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
