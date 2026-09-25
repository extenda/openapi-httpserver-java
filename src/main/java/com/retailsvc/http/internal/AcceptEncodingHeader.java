package com.retailsvc.http.internal;

import com.retailsvc.http.ContentCoding;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Parses {@code Accept-Encoding} request header values (RFC 9110 §12.5.3). */
public final class AcceptEncodingHeader {

  private static final double DEFAULT_WEIGHT = 1.0;

  private AcceptEncodingHeader() {}

  /**
   * Chooses the coding the client weights highest among {@code candidates}, which arrive in server
   * preference order, so the earlier candidate wins a tie. Empty means send the body uncoded — also
   * the answer for an absent header, since not asking for a coding is not the same as accepting
   * any.
   *
   * <p>A coding the client names outranks its {@code *} entry, so {@code gzip;q=0} refuses gzip
   * even beside a positive wildcard. A weight of zero refuses, and a name listed twice takes its
   * higher weight.
   */
  public static Optional<ContentCoding> select(String header, List<ContentCoding> candidates) {
    if (header == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Double> weights = weights(header);
    ContentCoding best = null;
    double bestWeight = 0;
    for (ContentCoding candidate : candidates) {
      double weight = weightOf(candidate, weights);
      if (weight > bestWeight) {
        best = candidate;
        bestWeight = weight;
      }
    }
    return Optional.ofNullable(best);
  }

  /** The weight the client gave each coding it listed, keeping the higher for a repeated one. */
  private static Map<String, Double> weights(String header) {
    Map<String, Double> weights = new HashMap<>();
    for (String token : header.split(",")) {
      int semi = token.indexOf(';');
      String coding = (semi < 0 ? token : token.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
      if (!coding.isEmpty()) {
        weights.merge(coding, weight(token), Math::max);
      }
    }
    return weights;
  }

  /** A candidate's weight from its own name or an alias, and only failing both, the wildcard. */
  private static double weightOf(ContentCoding coding, Map<String, Double> weights) {
    Double listed = weights.get(coding.token());
    for (String alias : coding.aliases()) {
      Double aliased = weights.get(alias);
      if (aliased != null && (listed == null || aliased > listed)) {
        listed = aliased;
      }
    }
    if (listed != null) {
      return listed;
    }
    return weights.getOrDefault("*", 0.0);
  }

  /**
   * Reads the {@code q} weight from a token. An absent or unparsable weight reads as the default
   * 1.0 — a malformed header should not silently disable compression.
   */
  private static double weight(String token) {
    String weight = ContentTypeHeader.parameter(token, "q").orElse(null);
    if (weight == null) {
      return DEFAULT_WEIGHT;
    }
    try {
      return Double.parseDouble(weight);
    } catch (NumberFormatException _) {
      return DEFAULT_WEIGHT;
    }
  }
}
