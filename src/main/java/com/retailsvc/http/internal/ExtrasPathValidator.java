package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class ExtrasPathValidator {

  private static final Pattern ENCODED_BLOCKLIST =
      Pattern.compile("(?i)%(?:25|2e|2f|5c|00|0[1-9a-f]|1[0-9a-f]|7f)");

  private ExtrasPathValidator() {}

  public static String validateAndDecode(URI uri) {
    String raw = uri.getRawPath();
    if (raw == null) {
      throw new BadRequestException("missing path");
    }
    if (ENCODED_BLOCKLIST.matcher(raw).find()) {
      throw new BadRequestException("path contains disallowed percent-encoded sequence");
    }
    if (raw.indexOf('\\') >= 0) {
      throw new BadRequestException("path contains backslash");
    }
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new BadRequestException("path contains control character");
      }
    }

    String decoded;
    try {
      decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("malformed percent-encoding");
    }

    for (int i = 0; i < decoded.length(); i++) {
      char c = decoded.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new BadRequestException("decoded path contains control character");
      }
    }

    String[] segments = decoded.substring(decoded.startsWith("/") ? 1 : 0).split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      String s = segments[i];
      if (s.isEmpty() && i != segments.length - 1) {
        throw new BadRequestException("empty path segment");
      }
      if (".".equals(s) || "..".equals(s)) {
        throw new BadRequestException("path traversal segment");
      }
    }

    return decoded;
  }
}
