package com.retailsvc.http.internal;

import com.retailsvc.http.spec.MediaType;
import java.util.Map;
import java.util.Optional;

/**
 * Matches a request's {@code Content-Type} against the media types an operation declares under
 * {@code requestBody.content}.
 *
 * <p>The most specific declaration wins: an exact media type, then its {@code type/*} range, then
 * {@code *}{@code /*}. Comparison ignores case. A request without a {@code Content-Type} is taken
 * as {@code application/json} when the operation declares it — the long-standing default — and
 * otherwise as {@code application/octet-stream}, which RFC 9110 §8.3 lets a recipient assume.
 */
final class RequestContentType {

  static final String JSON = "application/json";
  static final String OCTET_STREAM = "application/octet-stream";

  private RequestContentType() {}

  /**
   * The request's media type and the declaration it matched.
   *
   * @param mediaType the request's bare media type, lower-cased, or the default for a missing
   *     header
   * @param declared the matching key under {@code requestBody.content}, as written in the spec
   * @param content the matching declaration
   */
  record Match(String mediaType, String declared, MediaType content) {}

  /** The request's bare media type: from the header, or the default when there is none. */
  static String mediaType(String header, Map<String, MediaType> content) {
    if (header != null) {
      return ContentTypeHeader.mediaType(header);
    }
    return find(content, JSON).isPresent() ? JSON : OCTET_STREAM;
  }

  /** Matches {@code header} against {@code content}; empty when nothing declared accepts it. */
  static Optional<Match> match(String header, Map<String, MediaType> content) {
    String mediaType = mediaType(header, content);
    int slash = mediaType.indexOf('/');
    Optional<String> declared = find(content, mediaType);
    if (declared.isEmpty() && slash > 0) {
      declared = find(content, mediaType.substring(0, slash) + "/*");
    }
    if (declared.isEmpty()) {
      declared = find(content, "*/*");
    }
    return declared.map(key -> new Match(mediaType, key, content.get(key)));
  }

  private static Optional<String> find(Map<String, MediaType> content, String mediaType) {
    if (content.containsKey(mediaType)) {
      return Optional.of(mediaType);
    }
    return content.keySet().stream().filter(mediaType::equalsIgnoreCase).findFirst();
  }
}
