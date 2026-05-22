package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import java.net.URI;
import java.util.regex.Pattern;

public final class ExtrasPathValidator {

  private static final Pattern ENCODED_BLOCKLIST =
      Pattern.compile("(?i)%(?:25|2e|2f|5c|00|0[1-9a-f]|1[0-9a-f]|7f)");

  private ExtrasPathValidator() {}

  public static String validateAndDecode(URI uri) {
    String raw = uri.getRawPath();
    validateRaw(raw);

    String decoded = uri.getPath();
    if (decoded == null) {
      throw new BadRequestException("missing path");
    }
    rejectControlChars(decoded, "decoded path contains control character");
    validateSegments(decoded);
    return decoded;
  }

  private static void validateRaw(String raw) {
    if (raw == null) {
      throw new BadRequestException("missing path");
    }
    if (ENCODED_BLOCKLIST.matcher(raw).find()) {
      throw new BadRequestException("path contains disallowed percent-encoded sequence");
    }
    if (raw.indexOf('\\') >= 0) {
      throw new BadRequestException("path contains backslash");
    }
    rejectControlChars(raw, "path contains control character");
  }

  private static void rejectControlChars(String s, String message) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw new BadRequestException(message);
      }
    }
  }

  private static void validateSegments(String decoded) {
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
  }
}
