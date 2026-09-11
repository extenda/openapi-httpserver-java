package com.retailsvc.http.internal;

import com.retailsvc.http.ContentCoding;
import java.util.Locale;
import java.util.Map;

/** Classifies a request {@code Content-Encoding} against the codings the server can decode. */
public final class ContentEncodingHeader {

  private ContentEncodingHeader() {}

  /** How a request body is coded, as far as this server is concerned. */
  public sealed interface RequestCoding {

    /** Absent, blank, or the {@code identity} no-op: the body is already plain. */
    record Identity() implements RequestCoding {}

    /** A single coding this server can decode. */
    record Coded(ContentCoding coding) implements RequestCoding {}

    /** A coding this server cannot decode, or two stacked; the caller renders 415. */
    record Unsupported() implements RequestCoding {}
  }

  /**
   * Classifies the header value against {@code decoders}, keyed by lower-case token. {@code null},
   * blank and {@code identity} are all {@link RequestCoding.Identity}; a single known coding —
   * optionally alongside {@code identity} — is {@link RequestCoding.Coded}. An unknown coding, or
   * two stacked, is {@link RequestCoding.Unsupported}.
   */
  public static RequestCoding parse(String header, Map<String, ContentCoding> decoders) {
    if (header == null) {
      return new RequestCoding.Identity();
    }
    ContentCoding found = null;
    for (String token : header.split(",")) {
      String name = token.trim().toLowerCase(Locale.ROOT);
      if (name.isEmpty() || "identity".equals(name)) {
        continue;
      }
      if (found != null) {
        return new RequestCoding.Unsupported();
      }
      found = decoders.get(name);
      if (found == null) {
        return new RequestCoding.Unsupported();
      }
    }
    return found == null ? new RequestCoding.Identity() : new RequestCoding.Coded(found);
  }
}
