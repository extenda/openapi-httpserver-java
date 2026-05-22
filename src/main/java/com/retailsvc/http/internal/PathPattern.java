package com.retailsvc.http.internal;

import java.util.regex.Pattern;

public final class PathPattern {

  private final String raw;
  private final Pattern regex;
  private final boolean wildcard;

  private PathPattern(String raw, Pattern regex, boolean wildcard) {
    this.raw = raw;
    this.regex = regex;
    this.wildcard = wildcard;
  }

  public static PathPattern compile(String raw) {
    if (raw == null || !raw.startsWith("/")) {
      throw new IllegalArgumentException("path must start with '/': " + raw);
    }
    String[] segments = raw.substring(1).split("/", -1);
    StringBuilder rx = new StringBuilder("^");
    boolean hasWildcard = false;
    String prev = null;
    for (int i = 0; i < segments.length; i++) {
      String seg = segments[i];
      validateSegment(seg, prev, i, segments.length, raw);
      boolean trailing = i == segments.length - 1;
      hasWildcard |= appendSegment(rx, seg, trailing);
      prev = seg;
    }
    rx.append("$");
    return new PathPattern(raw, Pattern.compile(rx.toString()), hasWildcard);
  }

  private static void validateSegment(String seg, String prev, int i, int total, String raw) {
    boolean trailingEmptyAllowed = i == total - 1 && total > 1 && raw.endsWith("/");
    if (seg.isEmpty() && !trailingEmptyAllowed) {
      throw new IllegalArgumentException("empty segment in path: " + raw);
    }
    if (seg.contains("*") && !seg.equals("*") && !seg.equals("**")) {
      throw new IllegalArgumentException(
          "'*' and '**' must be a whole segment, not " + seg + " in " + raw);
    }
    if ("**".equals(seg) && "**".equals(prev)) {
      throw new IllegalArgumentException("adjacent '**' segments in " + raw);
    }
  }

  private static boolean appendSegment(StringBuilder rx, String seg, boolean trailing) {
    switch (seg) {
      case "*" -> rx.append("/[^/]+");
      case "**" -> rx.append(trailing ? "/.*" : "/.+");
      default -> {
        rx.append("/").append(Pattern.quote(seg));
        return false;
      }
    }
    return true;
  }

  public boolean hasWildcard() {
    return wildcard;
  }

  public boolean matches(String path) {
    return regex.matcher(path).matches();
  }

  public String raw() {
    return raw;
  }
}
