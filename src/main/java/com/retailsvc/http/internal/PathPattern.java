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
      if (seg.isEmpty()
          && !(i == segments.length - 1 && segments.length > 1 && raw.endsWith("/"))) {
        throw new IllegalArgumentException("empty segment in path: " + raw);
      }
      if (seg.contains("*") && !seg.equals("*") && !seg.equals("**")) {
        throw new IllegalArgumentException(
            "'*' and '**' must be a whole segment, not " + seg + " in " + raw);
      }
      if ("**".equals(seg) && "**".equals(prev)) {
        throw new IllegalArgumentException("adjacent '**' segments in " + raw);
      }
      boolean trailing = i == segments.length - 1;
      switch (seg) {
        case "*" -> {
          rx.append("/[^/]+");
          hasWildcard = true;
        }
        case "**" -> {
          if (trailing) {
            // Slash is required; anything (including empty string) may follow it.
            rx.append("/.*");
          } else {
            // At least one character and a slash must appear before the next segment.
            rx.append("/.+");
          }
          hasWildcard = true;
        }
        default -> rx.append("/").append(Pattern.quote(seg));
      }
      prev = seg;
    }
    rx.append("$");
    return new PathPattern(raw, Pattern.compile(rx.toString()), hasWildcard);
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
