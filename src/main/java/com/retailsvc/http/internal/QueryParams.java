package com.retailsvc.http.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a raw (percent-encoded) URI query string into decoded parameters. Splitting on {@code &}
 * and {@code =} happens <em>before</em> per-component decoding, so a percent-encoded separator
 * ({@code %26}, {@code %3D}) stays inside a value instead of becoming a delimiter. Request
 * validation and handler consumption both parse through here, so the value validated is exactly the
 * value the handler receives.
 */
public final class QueryParams {

  private QueryParams() {}

  /**
   * Parses {@code rawQuery} (the undecoded query component, e.g. {@code URI.getRawQuery()}) into a
   * name→value map. Values are URL-decoded with UTF-8. For repeated names the first occurrence
   * wins.
   */
  public static Map<String, String> parse(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq < 0 ? pair : pair.substring(0, eq);
      String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
      out.putIfAbsent(
          URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
          URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
    }
    return out;
  }
}
