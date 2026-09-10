package com.retailsvc.http.internal;

import java.util.Locale;

/** Parses {@code Accept-Encoding} request header values (RFC 9110 §12.5.3). */
public final class AcceptEncodingHeader {

  private static final String GZIP = "gzip";
  private static final String X_GZIP = "x-gzip";
  private static final String WILDCARD = "*";
  private static final String QUALITY = "q";
  private static final double DEFAULT_QUALITY = 1.0;

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
    Boolean gzipAccepted = null;
    Boolean wildcardAccepted = null;
    for (String token : header.split(",")) {
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int semi = trimmed.indexOf(';');
      String coding =
          (semi < 0 ? trimmed : trimmed.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
      boolean accepted = quality(semi < 0 ? null : trimmed.substring(semi + 1)) > 0;
      if (GZIP.equals(coding) || X_GZIP.equals(coding)) {
        gzipAccepted = gzipAccepted == null ? accepted : gzipAccepted || accepted;
      } else if (WILDCARD.equals(coding)) {
        wildcardAccepted = wildcardAccepted == null ? accepted : wildcardAccepted || accepted;
      }
    }
    if (gzipAccepted != null) {
      return gzipAccepted;
    }
    return wildcardAccepted != null && wildcardAccepted;
  }

  /**
   * Reads the {@code q} weight from a token's parameter list. An absent or unparsable weight is
   * read as the default 1.0 — a malformed header should not silently disable compression.
   */
  private static double quality(String parameters) {
    if (parameters == null) {
      return DEFAULT_QUALITY;
    }
    for (String parameter : parameters.split(";")) {
      String trimmed = parameter.trim();
      int equals = trimmed.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String name = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
      if (QUALITY.equals(name)) {
        try {
          return Double.parseDouble(trimmed.substring(equals + 1).trim());
        } catch (NumberFormatException e) {
          return DEFAULT_QUALITY;
        }
      }
    }
    return DEFAULT_QUALITY;
  }
}
