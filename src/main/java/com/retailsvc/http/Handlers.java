package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.retailsvc.http.internal.HealthRenderer;
import com.retailsvc.http.internal.ProblemDetail;
import com.retailsvc.http.internal.ProblemDetailRenderer;
import com.retailsvc.http.internal.ResourceSource;
import com.retailsvc.http.spec.HttpMethod;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);
  private static final String ALLOW = "Allow";

  private Handlers() {}

  /**
   * Response decorator that adds two browser-hardening headers to every response:
   *
   * <ul>
   *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME sniffing.
   *   <li>{@code Cross-Origin-Resource-Policy: same-origin} — blocks cross-origin reads of the
   *       response body, mitigating Spectre-class side-channel attacks.
   * </ul>
   *
   * <p>Existing headers with the same names are preserved, so a handler that sets either header
   * keeps its value. Wire it in with {@code
   * OpenApiServer.builder().responseDecorator(Handlers.securityHeadersDecorator())}.
   */
  public static ResponseDecorator securityHeadersDecorator() {
    return (request, response) -> {
      Response decorated = response;
      if (!response.headers().containsKey("X-Content-Type-Options")) {
        decorated = decorated.withHeader("X-Content-Type-Options", "nosniff");
      }
      if (!response.headers().containsKey("Cross-Origin-Resource-Policy")) {
        decorated = decorated.withHeader("Cross-Origin-Resource-Policy", "same-origin");
      }
      return decorated;
    };
  }

  /**
   * Returns a {@link RequestHandler} that answers CORS preflight {@code OPTIONS} requests for any
   * path the caller wires it under (typically via {@code
   * OpenApiServer.builder().extraRoute("/api/*", Handlers.corsPreflightHandler(...))}).
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
  public static RequestHandler corsPreflightHandler(
      List<String> allowedOrigins,
      List<HttpMethod> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      Duration maxAge) {
    Objects.requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    Set<String> origins = Set.copyOf(allowedOrigins);
    return corsPreflightHandler(
        origins::contains, allowedMethods, allowedHeaders, allowCredentials, maxAge);
  }

  /**
   * Predicate-based overload of {@link #corsPreflightHandler(List, List, List, boolean, Duration)}
   * for callers that need dynamic origin policy (regex, suffix match, config lookup).
   */
  public static RequestHandler corsPreflightHandler(
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

  public static ExceptionHandler defaultExceptionHandler() {
    return t ->
        switch (t) {
          case ValidationException ve ->
              Response.bytes(
                  HTTP_BAD_REQUEST,
                  ProblemDetailRenderer.renderJson(ProblemDetail.forValidation(ve.error())),
                  "application/problem+json");
          case BadRequestException bre ->
              Response.bytes(
                  bre.status(),
                  ProblemDetailRenderer.renderJson(ProblemDetail.forBadRequest(bre)),
                  "application/problem+json");
          case NotFoundException _ -> Response.notFound();
          case MethodNotAllowedException mna ->
              Response.status(HTTP_BAD_METHOD)
                  .withHeader(
                      ALLOW,
                      mna.allowed().stream().map(Enum::name).collect(Collectors.joining(", ")));
          default -> {
            LOG.error("Unhandled exception in handler", t);
            yield Response.status(HTTP_INTERNAL_ERROR);
          }
        };
  }

  /** Returns 204 No Content on GET/HEAD; 405 with {@code Allow: GET, HEAD} otherwise. */
  public static RequestHandler aliveHandler() {
    return req ->
        switch (req.method()) {
          case GET, HEAD -> Response.empty();
          default -> Response.status(HTTP_BAD_METHOD).withHeader(ALLOW, "GET, HEAD");
        };
  }

  /**
   * Health endpoint handler. Accepts GET and HEAD; returns 200 with {@code application/json} body
   * when the supplied probe reports up (all dependencies up, or no dependencies), and 503 with the
   * same body shape otherwise. A probe that throws a {@link RuntimeException} or returns {@code
   * null} is mapped to a {@code Down} response with an empty dependency list (and 503); the failure
   * is never propagated to the default exception handler.
   *
   * <p>The wire shape is
   *
   * <pre>{@code
   * {"outcome":"Up","dependencies":[{"id":"jdbc","status":"Up"}]}
   * }</pre>
   *
   * <p>The body is rendered by a built-in writer; no JSON library on the classpath is required.
   *
   * @param probe supplier of the current {@link HealthOutcome}
   */
  public static RequestHandler healthHandler(Supplier<HealthOutcome> probe) {
    Objects.requireNonNull(probe, "probe");
    return req -> {
      if (req.method() != GET && req.method() != HEAD) {
        return Response.status(HTTP_BAD_METHOD).withHeader(ALLOW, "GET, HEAD");
      }
      boolean up;
      List<Dependency> dependencies;
      try {
        HealthOutcome outcome = Objects.requireNonNull(probe.get(), "Health probe returned null");
        up = outcome.up();
        dependencies = outcome.dependencies();
      } catch (RuntimeException e) {
        LOG.warn("Health probe failed", e);
        up = false;
        dependencies = List.of();
      }
      byte[] body = HealthRenderer.renderJson(up, dependencies).getBytes(UTF_8);
      int status = up ? HTTP_OK : HTTP_UNAVAILABLE;
      return Response.bytes(status, body, "application/json");
    };
  }

  /**
   * Serves a classpath resource as a streaming response. Content-Type is inferred from the file
   * extension. Existence and length are resolved at construction; a missing resource fails
   * immediately with {@link IllegalArgumentException}. The resource is opened and closed per
   * request — the handler owns the stream lifecycle.
   *
   * @param classpathResource absolute classpath path, e.g. {@code /schemas/v1/openapi.yaml}
   */
  public static RequestHandler resourceHandler(String classpathResource) {
    return resourceHandler(ResourceSource.ofClasspath(classpathResource));
  }

  /**
   * Serves a filesystem file as a streaming response. Content-Type is inferred from the file
   * extension. Existence and length are resolved at construction; a missing file fails immediately
   * with {@link IllegalArgumentException}. The file is opened and closed per request.
   */
  public static RequestHandler resourceHandler(Path file) {
    return resourceHandler(ResourceSource.ofFile(file));
  }

  private static RequestHandler resourceHandler(ResourceSource source) {
    long length = source.length();
    String contentType = source.contentType();
    return req ->
        switch (req.method()) {
          case GET ->
              Response.stream(
                  HTTP_OK,
                  length,
                  contentType,
                  out -> {
                    try (InputStream in = source.open()) {
                      in.transferTo(out);
                    }
                  });
          case HEAD ->
              Response.status(HTTP_OK)
                  .withContentType(contentType)
                  .withHeader("Content-Length", String.valueOf(length));
          default -> Response.status(HTTP_BAD_METHOD).withHeader(ALLOW, "GET, HEAD");
        };
  }
}
