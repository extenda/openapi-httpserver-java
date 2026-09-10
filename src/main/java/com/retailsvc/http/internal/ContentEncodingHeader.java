package com.retailsvc.http.internal;

import java.util.Locale;

/** Classifies a request {@code Content-Encoding} into the codings the server can decode. */
public final class ContentEncodingHeader {

  private ContentEncodingHeader() {}

  /**
   * The coding applied to a request body: none (absent or the {@code identity} no-op), a single
   * gzip, or one this server cannot decode and the caller renders 415 for.
   */
  public enum Coding {
    NONE,
    GZIP,
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
      if (coding.isEmpty() || "identity".equals(coding)) {
        continue;
      }
      if (result != Coding.NONE) {
        return Coding.UNSUPPORTED;
      }
      if ("gzip".equals(coding) || "x-gzip".equals(coding)) {
        result = Coding.GZIP;
      } else {
        return Coding.UNSUPPORTED;
      }
    }
    return result;
  }
}
