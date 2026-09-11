package com.retailsvc.http.internal;

import com.retailsvc.http.ContentCoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

/** Which responses are worth coding, and coding a whole body at once. */
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
   * Whether a response of this content type is worth compressing. Already-compressed payloads gain
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

  /** Codes {@code body} completely with {@code coding}. */
  public static byte[] encode(ContentCoding coding, byte[] body) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (OutputStream coded = coding.encode(out)) {
      coded.write(body);
    }
    return out.toByteArray();
  }
}
