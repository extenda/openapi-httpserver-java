package com.retailsvc.http.internal;

import static java.util.Objects.requireNonNull;

import com.retailsvc.http.ContentCoding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The content codings a server decodes and applies: the built-in gzip plus whatever the caller
 * registered, split by direction.
 *
 * @param decoders request codings keyed by every token that names them, aliases included
 * @param encoders response codings in server preference order, the built-in gzip last
 */
public record ContentCodings(Map<String, ContentCoding> decoders, List<ContentCoding> encoders) {

  private static final ContentCoding GZIP = new GzipCoding();
  private static final Set<String> RESERVED = Set.of("gzip", "x-gzip", "identity", "*");
  private static final String TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~";

  public ContentCodings {
    decoders = Map.copyOf(decoders);
    encoders = List.copyOf(encoders);
  }

  /**
   * Builds the registry from codings already accepted by {@link #requireRegistrable}. Registered
   * codings come before gzip, so a client that weights them equally gets the one the caller added.
   */
  public static ContentCodings of(
      List<ContentCoding> requestCodings, List<ContentCoding> responseCodings) {
    Map<String, ContentCoding> decoders = new HashMap<>();
    for (ContentCoding coding : requestCodings) {
      names(coding).forEach(name -> decoders.put(name, coding));
    }
    names(GZIP).forEach(name -> decoders.put(name, GZIP));
    List<ContentCoding> encoders = new ArrayList<>(responseCodings);
    encoders.add(GZIP);
    return new ContentCodings(decoders, encoders);
  }

  /**
   * Rejects a coding that cannot be registered alongside {@code registered}: a token or alias that
   * is not a lower-case RFC 9110 token, one that is reserved, or one already claimed. Tokens are
   * written verbatim into response headers, so the token check also keeps those headers clean.
   */
  public static void requireRegistrable(ContentCoding coding, List<ContentCoding> registered) {
    requireNonNull(coding, "coding must not be null");
    Set<String> names = names(coding);
    names.forEach(ContentCodings::requireToken);
    for (ContentCoding other : registered) {
      for (String name : names(other)) {
        if (names.contains(name)) {
          throw new IllegalStateException("duplicate content coding '" + name + "'");
        }
      }
    }
  }

  private static Set<String> names(ContentCoding coding) {
    Set<String> names = new LinkedHashSet<>();
    names.add(coding.token());
    names.addAll(requireNonNull(coding.aliases(), "content coding aliases must not be null"));
    return names;
  }

  private static void requireToken(String token) {
    requireNonNull(token, "content coding token must not be null");
    if (token.isEmpty() || !token.chars().allMatch(ContentCodings::isTokenChar)) {
      throw new IllegalArgumentException(
          "content coding '" + token + "' is not a lower-case RFC 9110 token");
    }
    if (RESERVED.contains(token)) {
      throw new IllegalArgumentException("content coding '" + token + "' is reserved");
    }
  }

  private static boolean isTokenChar(int c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || TOKEN_SYMBOLS.indexOf(c) >= 0;
  }
}
