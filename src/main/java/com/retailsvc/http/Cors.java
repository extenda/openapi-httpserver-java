package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;

import com.retailsvc.http.spec.HttpMethod;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * CORS support. Currently exposes {@link #preflightHandler(List, List, List, boolean, Duration) a
 * preflight handler} that answers browser {@code OPTIONS} preflight requests; future cross-origin
 * helpers (e.g. a response decorator for {@code Access-Control-Expose-Headers}) will live here too.
 */
public final class Cors {

  private static final String ALLOW = "Allow";

  private Cors() {}

  /**
   * Returns a {@link RequestHandler} that answers CORS preflight {@code OPTIONS} requests for any
   * path the caller wires it under (typically via {@code
   * OpenApiServer.builder().extraRoute("/api/*", Cors.preflightHandler(...))}).
   *
   * <p>Requests are validated in order: origin against {@code allowedOrigins} (exact match), {@code
   * Access-Control-Request-Method} against {@code allowedMethods}, and each header in {@code
   * Access-Control-Request-Headers} against {@code allowedHeaders} (case-insensitive). A non-{@code
   * OPTIONS} request yields {@code 405} with {@code Allow: OPTIONS}; a missing {@code Origin} or
   * {@code Access-Control-Request-Method} header yields {@code 400}; any disallowed origin / method
   * / header yields {@code 403} with no CORS headers (the browser then blocks the request).
   *
   * <p>On success the response is {@code 204 No Content} with {@code Access-Control-Allow-Origin}
   * echoing the request's {@code Origin}, the configured method and header allowlists, and {@code
   * Vary: Origin} so caches segment by origin. {@code Access-Control-Allow-Credentials} and {@code
   * Access-Control-Max-Age} are emitted only when enabled.
   *
   * @param allowedOrigins exact-match origin allowlist; never {@code null}
   * @param allowedMethods non-empty list of methods to advertise in {@code Allow-Methods}
   * @param allowedHeaders header allowlist (matched case-insensitively); may be empty (then {@code
   *     Access-Control-Allow-Headers} is omitted)
   * @param allowCredentials whether to emit {@code Access-Control-Allow-Credentials: true}
   * @param maxAge {@code Access-Control-Max-Age} value; {@code null} omits the header
   */
  public static RequestHandler preflightHandler(
      List<String> allowedOrigins,
      List<HttpMethod> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      Duration maxAge) {
    Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    Set<String> origins = Set.copyOf(allowedOrigins);
    return preflightHandler(
        origins::contains, allowedMethods, allowedHeaders, allowCredentials, maxAge);
  }

  /**
   * Predicate-based overload of {@link #preflightHandler(List, List, List, boolean, Duration)} for
   * callers that need dynamic origin policy (regex, suffix match, config lookup).
   */
  public static RequestHandler preflightHandler(
      Predicate<String> originAllowed,
      List<HttpMethod> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      Duration maxAge) {
    Objects.requireNonNull(originAllowed, "originAllowed must not be null");
    Objects.requireNonNull(allowedMethods, "allowedMethods must not be null");
    Objects.requireNonNull(allowedHeaders, "allowedHeaders must not be null");
    if (allowedMethods.isEmpty()) {
      throw new IllegalArgumentException("allowedMethods must not be empty");
    }
    if (maxAge != null && (maxAge.isNegative() || maxAge.getSeconds() > Integer.MAX_VALUE)) {
      throw new IllegalArgumentException(
          "maxAge must be non-negative and fit in an int number of seconds, got " + maxAge);
    }

    String allowMethodsHeader =
        allowedMethods.stream().map(Enum::name).collect(Collectors.joining(", "));
    String allowHeadersHeader = String.join(", ", allowedHeaders);
    Set<String> headerAllowlistLower =
        allowedHeaders.stream()
            .map(h -> h.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    String maxAgeHeader = maxAge == null ? null : Long.toString(maxAge.getSeconds());
    boolean emitAllowHeaders = !allowedHeaders.isEmpty();

    return req -> {
      if (req.method() != OPTIONS) {
        return Response.status(HTTP_BAD_METHOD).withHeader(ALLOW, "OPTIONS");
      }
      String origin = requireHeader(req, "Origin");
      String requestMethod = requireHeader(req, "Access-Control-Request-Method");
      if (!isPreflightAllowed(
          req, origin, requestMethod, originAllowed, allowedMethods, headerAllowlistLower)) {
        return Response.status(HTTP_FORBIDDEN);
      }
      return buildPreflightSuccess(
          origin,
          allowMethodsHeader,
          allowHeadersHeader,
          emitAllowHeaders,
          allowCredentials,
          maxAgeHeader);
    };
  }

  private static String requireHeader(Request req, String name) {
    return req.header(name)
        .orElseThrow(
            () -> new BadRequestException("CORS preflight is missing the " + name + " header"));
  }

  private static boolean isPreflightAllowed(
      Request req,
      String origin,
      String requestMethod,
      Predicate<String> originAllowed,
      List<HttpMethod> allowedMethods,
      Set<String> headerAllowlistLower) {
    if (!originAllowed.test(origin)) {
      return false;
    }
    HttpMethod parsed = parseMethodOrNull(requestMethod);
    if (parsed == null || !allowedMethods.contains(parsed)) {
      return false;
    }
    return requestedHeadersAllowed(req, headerAllowlistLower);
  }

  private static HttpMethod parseMethodOrNull(String s) {
    try {
      return HttpMethod.parse(s);
    } catch (IllegalArgumentException _) {
      // Unknown method token — treated as disallowed by the caller.
      return null;
    }
  }

  private static boolean requestedHeadersAllowed(Request req, Set<String> allowedLower) {
    String requested = req.header("Access-Control-Request-Headers").orElse("");
    for (String raw : requested.split(",")) {
      String h = raw.trim().toLowerCase(Locale.ROOT);
      if (h.isEmpty()) {
        continue;
      }
      if (!allowedLower.contains(h)) {
        return false;
      }
    }
    return true;
  }

  private static Response buildPreflightSuccess(
      String origin,
      String allowMethodsHeader,
      String allowHeadersHeader,
      boolean emitAllowHeaders,
      boolean allowCredentials,
      String maxAgeHeader) {
    Response resp =
        Response.status(HTTP_NO_CONTENT)
            .withHeader("Access-Control-Allow-Origin", origin)
            .withHeader("Access-Control-Allow-Methods", allowMethodsHeader)
            .withHeader("Vary", "Origin");
    if (emitAllowHeaders) {
      resp = resp.withHeader("Access-Control-Allow-Headers", allowHeadersHeader);
    }
    if (allowCredentials) {
      resp = resp.withHeader("Access-Control-Allow-Credentials", "true");
    }
    if (maxAgeHeader != null) {
      resp = resp.withHeader("Access-Control-Max-Age", maxAgeHeader);
    }
    return resp;
  }
}
