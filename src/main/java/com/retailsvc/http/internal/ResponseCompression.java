package com.retailsvc.http.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Response content-coding policy, and the gzip primitives the renderer writes through. */
public final class ResponseCompression {

  private static final Set<String> COMPRESSIBLE_TYPES =
      Set.of(
          "application/json",
          "application/xml",
          "application/yaml",
          "application/x-yaml",
          "application/javascript",
          "application/x-ndjson");

  private ResponseCompression() {}

  /**
   * Whether a response of this content type is worth gzipping. Already-compressed payloads gain
   * nothing, and {@code text/event-stream} must stay unbuffered so each event reaches the client as
   * it is written.
   *
   * <p>An absent content type is never compressible. It cannot be resolved through {@link
   * ContentTypeHeader#mediaType} here, because that reads {@code null} as {@code application/json}.
   */
  public static boolean isCompressible(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = ContentTypeHeader.mediaType(contentType);
    if (mediaType.startsWith("text/")) {
      return !"text/event-stream".equals(mediaType);
    }
    if (mediaType.endsWith("+json") || mediaType.endsWith("+xml") || mediaType.endsWith("+yaml")) {
      return true;
    }
    return COMPRESSIBLE_TYPES.contains(mediaType);
  }

  /** Deflates {@code body} into a complete gzip member. */
  public static byte[] gzip(byte[] body) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(body);
    }
    return out.toByteArray();
  }
}
