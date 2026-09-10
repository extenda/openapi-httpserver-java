package com.retailsvc.http.internal;

import java.util.Locale;

/** Classifies a request {@code Content-Encoding} into the codings the server can decode. */
public final class ContentEncodingHeader {

  private static final String IDENTITY_CODING = "identity";
  private static final String GZIP_CODING = "gzip";
  private static final String X_GZIP_CODING = "x-gzip";

  private ContentEncodingHeader() {}

  /** The content coding applied to a request body. */
  public enum Coding {
    /** No coding, or the explicit {@code identity} no-op. */
    NONE,
    /** A single gzip coding. */
    GZIP,
    /** A coding this server cannot decode; the caller renders 415. */
    UNSUPPORTED
  }

  /**
   * Classifies the header value. {@code null}, blank and {@code identity} are all {@link
   * Coding#NONE}; a single gzip coding — optionally alongside {@code identity} — is {@link
   * Coding#GZIP}. Anything else, including two stacked codings, is {@link Coding#UNSUPPORTED}.
   */
  public static Coding parse(String header) {
    if (header == null) {
      return Coding.NONE;
    }
    Coding result = Coding.NONE;
    for (String token : header.split(",")) {
      String coding = token.trim().toLowerCase(Locale.ROOT);
      if (coding.isEmpty() || IDENTITY_CODING.equals(coding)) {
        continue;
      }
      if (result != Coding.NONE) {
        return Coding.UNSUPPORTED;
      }
      if (GZIP_CODING.equals(coding) || X_GZIP_CODING.equals(coding)) {
        result = Coding.GZIP;
      } else {
        return Coding.UNSUPPORTED;
      }
    }
    return result;
  }
}
