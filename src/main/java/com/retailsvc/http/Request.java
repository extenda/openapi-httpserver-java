package com.retailsvc.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Read-only per-request handle passed to {@link RequestHandler}. Carries the parsed body, path
 * parameters, query parameters, headers, and operation ID.
 *
 * <p>{@code Request} is transport-neutral: it holds the body bytes, the raw query string, the path
 * parameter map, and a header lookup function. The transport adapter (today the built-in JDK {@code
 * HttpServer}, tomorrow potentially Netty or another backend) is responsible for extracting those
 * primitives from its own request representation. Handlers consume a {@code Request} and return a
 * {@link Response}.
 */
public final class Request {

  private static final String CONTENT_TYPE = "Content-Type";

  private final byte[] body;
  private final Object parsed;
  private final TypeMapper bodyMapper;
  private final String operationId;
  private final Map<String, String> pathParameters;
  private final String rawQuery;
  private final UnaryOperator<String> headerLookup;
  private final Map<String, Object> principals;
  private Map<String, String> queryParamCache;

  /**
   * Builds a {@code Request} from transport-neutral primitives. Adapters call this; handlers
   * receive the constructed instance.
   *
   * @param body raw request body bytes; never {@code null}, may be empty
   * @param parsed loose structural view of the body (Map / List / boxed primitive), or {@code null}
   * @param bodyMapper {@link TypeMapper} that produced {@code parsed}, used for typed conversion;
   *     may be {@code null} if there is no body
   * @param operationId the OpenAPI {@code operationId} the request was routed to
   * @param pathParameters path variables extracted by the router
   * @param rawQuery raw (percent-encoded) query string, or {@code null} if absent
   * @param headerLookup first-value, case-insensitive header lookup; returns {@code null} if absent
   */
  public Request(
      byte[] body,
      Object parsed,
      TypeMapper bodyMapper,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup) {
    this(body, parsed, bodyMapper, operationId, pathParameters, rawQuery, headerLookup, Map.of());
  }

  /**
   * Builds a {@code Request} from transport-neutral primitives with an explicit principals map.
   *
   * @param body raw request body bytes; never {@code null}, may be empty
   * @param parsed loose structural view of the body (Map / List / boxed primitive), or {@code null}
   * @param bodyMapper {@link TypeMapper} that produced {@code parsed}, used for typed conversion;
   *     may be {@code null} if there is no body
   * @param operationId the OpenAPI {@code operationId} the request was routed to
   * @param pathParameters path variables extracted by the router
   * @param rawQuery raw (percent-encoded) query string, or {@code null} if absent
   * @param headerLookup first-value, case-insensitive header lookup; returns {@code null} if absent
   * @param principals principals stashed by the security filter, keyed by scheme name
   */
  // Request is transport-neutral and assembled from primitives at the adapter boundary; collapsing
  // these into a holder type would just move the parameter count one level out without simplifying
  // the call site, so the 8-arg constructor is preferred over the rule's 7-param limit.
  @SuppressWarnings("java:S107")
  public Request(
      byte[] body,
      Object parsed,
      TypeMapper bodyMapper,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup,
      Map<String, Object> principals) {
    this.body = body;
    this.parsed = parsed;
    this.bodyMapper = bodyMapper;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.rawQuery = rawQuery;
    this.headerLookup = headerLookup;
    this.principals = Map.copyOf(principals);
  }

  public byte[] bytes() {
    return body;
  }

  /**
   * Loose structural view of the body (typically a {@code Map} / {@code List} / boxed primitive).
   */
  public Object parsed() {
    return parsed;
  }

  /**
   * Typed view of the body, deserialised into {@code type} by the request's body mapper.
   *
   * <p>Requires the registered {@link TypeMapper} for the request's {@code Content-Type} to
   * implement {@link TypedTypeMapper} — Jackson does, the built-in form and text mappers do not. If
   * the loose {@link #parsed()} value already is an instance of {@code type}, it is returned
   * directly without re-deserialising.
   *
   * @throws NullPointerException if {@code type} is null
   * @throws IllegalStateException if there is no body, or if the body mapper does not implement
   *     {@link TypedTypeMapper}
   */
  public <T> T asPojo(Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    if (body == null || body.length == 0) {
      throw new IllegalStateException("request has no body");
    }
    if (parsed != null && type.isInstance(parsed)) {
      return type.cast(parsed);
    }
    String contentType = headerLookup.apply(CONTENT_TYPE);
    if (bodyMapper instanceof TypedTypeMapper typed) {
      return typed.readAs(body, contentType, type);
    }
    throw new IllegalStateException(
        "body mapper for "
            + contentType
            + " does not support typed conversion; the mapper must implement TypedTypeMapper");
  }

  /**
   * Value of the {@code Content-Type} request header, or {@link Optional#empty()} if absent or
   * blank. Convenience for {@code header("Content-Type")} — the most frequently inspected header.
   */
  public Optional<String> contentType() {
    return header(CONTENT_TYPE);
  }

  public String operationId() {
    return operationId;
  }

  public Map<String, String> pathParams() {
    return pathParameters;
  }

  /** Value of the path parameter {@code name}, or {@code null} if absent. */
  public String pathParam(String name) {
    return pathParameters.get(name);
  }

  /**
   * First value of the request header {@code name}, or {@link Optional#empty()} if absent or blank.
   * Blank values are treated as missing so callers can write {@code req.header("X").map(...)}
   * without the extra {@code filter(v -> !v.isBlank())} step.
   */
  public Optional<String> header(String name) {
    String raw = headerLookup.apply(name);
    return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
  }

  /**
   * Raw (percent-encoded) query string from the request URI, or {@code null} if the URI has no
   * query component.
   */
  public String rawQuery() {
    return rawQuery;
  }

  /**
   * Decoded query parameters keyed by name. Empty if the URI has no query. For repeated keys, the
   * first occurrence wins. Values are URL-decoded with UTF-8.
   */
  public Map<String, String> queryParams() {
    if (queryParamCache == null) {
      queryParamCache = parseQuery(rawQuery);
    }
    return queryParamCache;
  }

  /**
   * First decoded value for query parameter {@code name}, or {@link Optional#empty()} if absent or
   * blank. Blank values are treated as missing so callers can write {@code
   * req.queryParam("limit").map(Integer::parseInt).orElse(DEFAULT)} without the extra {@code
   * filter(v -> !v.isBlank())} step.
   */
  public Optional<String> queryParam(String name) {
    String raw = queryParams().get(name);
    return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
  }

  /**
   * Principals stashed by {@code SecurityFilter}, keyed by securityScheme name. Empty when the
   * request had no security requirements or when {@code useExternalAuthentication()} is set.
   */
  public Map<String, Object> principals() {
    return principals;
  }

  /** Convenience for the common single-scheme case. */
  public Optional<Object> principal(String schemeName) {
    return Optional.ofNullable(principals.get(schemeName));
  }

  /**
   * Returns a new {@code Request} identical to this one except with the supplied principals. Used
   * by {@code SecurityFilter} on success; the returned instance carries the principals through to
   * the {@link RequestHandler}.
   */
  public Request withPrincipals(Map<String, Object> principals) {
    return new Request(
        body, parsed, bodyMapper, operationId, pathParameters, rawQuery, headerLookup, principals);
  }

  private static Map<String, String> parseQuery(String query) {
    if (query == null || query.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq < 0 ? pair : pair.substring(0, eq);
      String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
      out.putIfAbsent(
          URLDecoder.decode(rawKey, StandardCharsets.UTF_8),
          URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
    }
    return out;
  }
}
