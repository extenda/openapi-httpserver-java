package com.retailsvc.http.internal;

import java.util.Locale;

/** Parses {@code Accept-Encoding} request header values (RFC 9110 §12.5.3). */
public final class AcceptEncodingHeader {

  private AcceptEncodingHeader() {}

  /**
   * Whether the client accepts a gzip-coded response. A {@code null}, blank, or unrelated header
   * yields {@code false}. An explicit {@code gzip;q=0} is a refusal and outranks a positive
   * wildcard; a wildcard applies only when gzip is not listed in its own right.
   */
  public static boolean acceptsGzip(String header) {
    if (header == null) {
      return false;
    }
    boolean gzipSeen = false;
    boolean gzipAccepted = false;
    boolean wildcardAccepted = false;
    for (String token : header.split(",")) {
      int semi = token.indexOf(';');
      String coding = (semi < 0 ? token : token.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
      boolean accepted = positiveWeight(token);
      if ("gzip".equals(coding) || "x-gzip".equals(coding)) {
        gzipSeen = true;
        gzipAccepted |= accepted;
      } else if ("*".equals(coding)) {
        wildcardAccepted |= accepted;
      }
    }
    return gzipSeen ? gzipAccepted : wildcardAccepted;
  }

  /**
   * Whether the token's {@code q} weight admits the coding. An absent or unparsable weight reads as
   * the default 1.0 — a malformed header should not silently disable compression.
   */
  private static boolean positiveWeight(String token) {
    String weight = ContentTypeHeader.parameter(token, "q").orElse(null);
    if (weight == null) {
      return true;
    }
    try {
      return Double.parseDouble(weight) > 0;
    } catch (NumberFormatException _) {
      return true;
    }
  }
}
