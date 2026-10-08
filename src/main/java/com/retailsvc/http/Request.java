package com.retailsvc.http;

import com.retailsvc.http.internal.QueryParams;
import com.retailsvc.http.spec.HttpMethod;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Read-only per-request handle passed to {@link RequestHandler}. Carries the HTTP method, parsed
 * body, path parameters, query parameters, headers, and operation ID.
 *
 * <p>{@code Request} is transport-neutral: it holds the body (bytes, or an {@link InputStream} for
 * a {@link StreamingRequestHandler}), the raw query string, the path parameter map, and a header
 * lookup function. The transport adapter (today the built-in JDK {@code HttpServer}, tomorrow
 * potentially Netty or another backend) is responsible for extracting those primitives from its own
 * request representation. Handlers consume a {@code Request} and return a {@link Response}.
 */
public final class Request {

  private static final String CONTENT_TYPE = "Content-Type";
  private static final byte[] NO_BYTES = new byte[0];

  private final byte[] body;
  private final InputStream stream;
  private final Object parsed;
  private final TypeMapper bodyMapper;
  private final String operationId;
  private final Map<String, String> pathParameters;
  private final String rawQuery;
  private final HttpMethod method;
  private final UnaryOperator<String> headerLookup;
  private final Map<String, List<String>> headers;
  private final Map<String, Object> principals;
  private Map<String, String> queryParamCache;
  private final List<Runnable> afterHooks;

  /**
   * Builds a {@code Request} from transport-neutral primitives. Adapters call this; handlers
   * receive the constructed instance.
   *
   * <p>{@code method} is {@code null}; use the 9-arg constructor to supply it.
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
    this(
        body,
        parsed,
        bodyMapper,
        operationId,
        pathParameters,
        rawQuery,
        headerLookup,
        Map.of(),
        null);
  }

  /**
   * Builds a {@code Request} from transport-neutral primitives with an explicit principals map.
   *
   * <p>{@code method} is {@code null}; use the 9-arg constructor to supply it.
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
    this(
        body,
        parsed,
        bodyMapper,
        operationId,
        pathParameters,
        rawQuery,
        headerLookup,
        principals,
        null);
  }

  /**
   * Builds a {@code Request} from transport-neutral primitives with explicit principals and method.
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
   * @param method the HTTP method of the request. Never {@code null} when constructed through the
   *     normal request pipeline. {@code null} only when constructed via the legacy 7- or 8-argument
   *     constructors (kept for backward compatibility).
   */
  // Request is transport-neutral and assembled from primitives at the adapter boundary; collapsing
  // these into a holder type would just move the parameter count one level out without simplifying
  // the call site, so the 9-arg constructor is preferred over the rule's 7-param limit.
  @SuppressWarnings("java:S107")
  public Request(
      byte[] body,
      Object parsed,
      TypeMapper bodyMapper,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup,
      Map<String, Object> principals,
      HttpMethod method) {
    this.body = body;
    this.stream = null;
    this.parsed = parsed;
    this.bodyMapper = bodyMapper;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.rawQuery = rawQuery;
    this.method = method;
    this.headerLookup = headerLookup;
    this.headers = Map.of();
    this.principals = Map.copyOf(principals);
    this.afterHooks = new ArrayList<>();
  }

  // Private: lets streaming(...), withPrincipals(...) and withHeaders(...) set the body stream and
  // the header map, and lets the copies thread the after-hook queue through so that runnables
  // registered on either the original Request or a copy land in the same backing list.
  @SuppressWarnings("java:S107")
  private Request(
      byte[] body,
      InputStream stream,
      Object parsed,
      TypeMapper bodyMapper,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup,
      Map<String, Object> principals,
      HttpMethod method,
      List<Runnable> afterHooks,
      Map<String, List<String>> headers) {
    this.body = body;
    this.stream = stream;
    this.parsed = parsed;
    this.bodyMapper = bodyMapper;
    this.operationId = operationId;
    this.pathParameters = pathParameters;
    this.rawQuery = rawQuery;
    this.method = method;
    this.headerLookup = headerLookup;
    this.headers = headers;
    this.principals = Map.copyOf(principals);
    this.afterHooks = afterHooks;
  }

  /**
   * Builds a {@code Request} whose body is read from {@code body} as it arrives, as handed to a
   * {@link StreamingRequestHandler}. {@link #bytes()}, {@link #parsed()} and {@link #asPojo(Class)}
   * throw on the result; read it through {@link #bodyStream()}.
   *
   * @param body the request body — decoded of any {@code Content-Encoding} unless the handler opted
   *     out with {@link StreamingRequestHandler#decodeContent()}; never {@code null}
   * @param operationId the OpenAPI {@code operationId} the request was routed to, or {@code null}
   *     for an extra route
   * @param pathParameters path variables extracted by the router
   * @param rawQuery raw (percent-encoded) query string, or {@code null} if absent
   * @param headerLookup first-value, case-insensitive header lookup; returns {@code null} if absent
   * @param principals principals stashed by the security filter, keyed by scheme name
   * @param method the HTTP method of the request
   */
  public static Request streaming(
      InputStream body,
      String operationId,
      Map<String, String> pathParameters,
      String rawQuery,
      UnaryOperator<String> headerLookup,
      Map<String, Object> principals,
      HttpMethod method) {
    return new Request(
        null,
        Objects.requireNonNull(body, "body must not be null"),
        null,
        null,
        operationId,
        pathParameters,
        rawQuery,
        headerLookup,
        principals,
        method,
        new ArrayList<>(),
        Map.of());
  }

  /**
   * Raw request body bytes.
   *
   * @throws IllegalStateException if the body is streamed; use {@link #bodyStream()}
   */
  public byte[] bytes() {
    requireBuffered();
    return body;
  }

  /**
   * Loose structural view of the body (typically a {@code Map} / {@code List} / boxed primitive).
   *
   * @throws IllegalStateException if the body is streamed; use {@link #bodyStream()}
   */
  public Object parsed() {
    requireBuffered();
    return parsed;
  }

  /**
   * The request body as a stream. For a {@link StreamingRequestHandler} this is the body as it
   * arrives from the client: it can be read once, and must be read before the handler returns. For
   * any other handler it is a fresh stream over {@link #bytes()} on every call.
   */
  public InputStream bodyStream() {
    if (stream != null) {
      return stream;
    }
    return new ByteArrayInputStream(body != null ? body : NO_BYTES);
  }

  /**
   * Whether the body is streamed, as for a {@link StreamingRequestHandler}. When {@code true},
   * {@link #bytes()}, {@link #parsed()} and {@link #asPojo(Class)} throw; interceptors and hooks
   * that inspect the body should check this first.
   */
  public boolean isStreaming() {
    return stream != null;
  }

  private void requireBuffered() {
    if (stream != null) {
      throw new IllegalStateException("request body is streamed; read it through bodyStream()");
    }
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
   * @throws IllegalStateException if there is no body, if the body is streamed, or if the body
   *     mapper does not implement {@link TypedTypeMapper}
   */
  public <T> T asPojo(Class<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    requireBuffered();
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
   * Every request header, by name, with all of its values in arrival order. Lookups are
   * case-insensitive. For a body the server decoded, {@code Content-Encoding} is absent and {@code
   * Content-Length} gives the decoded length, or is absent when that is not known up front, as for
   * a {@link StreamingRequestHandler}.
   *
   * <p>Empty for a {@code Request} built from a header lookup function alone, which has no names to
   * list; see {@link #withHeaders(Map)}.
   */
  public Map<String, List<String>> headers() {
    return headers;
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
      queryParamCache = QueryParams.parse(rawQuery);
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
   * HTTP method of the request. Never {@code null} for requests routed through the standard
   * pipeline; {@code null} only when the {@code Request} was constructed via a legacy constructor
   * without a method.
   */
  public HttpMethod method() {
    return method;
  }

  /**
   * Returns a new {@code Request} identical to this one except with the supplied principals. Used
   * by {@code SecurityFilter} on success; the returned instance carries the principals through to
   * the {@link RequestHandler}.
   */
  public Request withPrincipals(Map<String, Object> principals) {
    return new Request(
        body,
        stream,
        parsed,
        bodyMapper,
        operationId,
        pathParameters,
        rawQuery,
        headerLookup,
        principals,
        method,
        afterHooks,
        headers);
  }

  /**
   * Returns a new {@code Request} identical to this one except that its headers come from {@code
   * headers}: both {@link #headers()} and {@link #header(String)} read from it, case-insensitively.
   * The server builds every request it hands to a handler this way; use it in tests to build a
   * {@code Request} whose headers can be listed.
   *
   * @param headers header values by name, in arrival order; never {@code null}
   */
  public Request withHeaders(Map<String, List<String>> headers) {
    TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    Map<String, List<String>> view = Collections.unmodifiableMap(copy);
    return new Request(
        body,
        stream,
        parsed,
        bodyMapper,
        operationId,
        pathParameters,
        rawQuery,
        name -> {
          List<String> values = view.get(name);
          return values == null || values.isEmpty() ? null : values.getFirst();
        },
        principals,
        method,
        afterHooks,
        view);
  }

  /**
   * Queues a {@link Runnable} to execute after the HTTP response has been sent to the client. Runs
   * on the request thread inside the library's request {@link ScopedValue} binding. Multiple calls
   * queue FIFO. Exceptions thrown by the runnable are logged at DEBUG and swallowed.
   *
   * <p>Calls made after the runner has snapshotted the queue (e.g. from inside a running hook, or
   * from a leaked {@code Request} reference held past the response) are silently ignored.
   *
   * @throws NullPointerException if {@code runnable} is null
   */
  public void afterResponse(Runnable runnable) {
    Objects.requireNonNull(runnable, "runnable must not be null");
    afterHooks.add(runnable);
  }

  /**
   * Returns an unmodifiable view of the queued after-response runnables. Intended for the framework
   * runner; consumers should use {@link #afterResponse(Runnable)} to register runnables rather than
   * inspecting this list directly.
   */
  public List<Runnable> afterHooks() {
    return Collections.unmodifiableList(afterHooks);
  }
}
