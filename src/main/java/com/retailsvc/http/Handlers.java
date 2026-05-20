package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

import com.retailsvc.http.internal.ClasspathResourceHandler;
import com.retailsvc.http.internal.MethodLimitedHandler;
import com.retailsvc.http.internal.ProblemDetail;
import com.sun.net.httpserver.HttpHandler;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);

  private Handlers() {}

  public static ExceptionHandler defaultExceptionHandler(TypeMapper jsonMapper) {
    Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    return t ->
        switch (t) {
          case ValidationException ve ->
              Response.bytes(
                  HTTP_BAD_REQUEST,
                  jsonMapper.writeTo(ProblemDetail.forValidation(ve.error())),
                  "application/problem+json");
          case BadRequestException bre ->
              Response.bytes(
                  bre.status(),
                  jsonMapper.writeTo(ProblemDetail.forBadRequest(bre)),
                  "application/problem+json");
          case NotFoundException _ -> Response.notFound();
          case MethodNotAllowedException mna ->
              Response.status(HTTP_BAD_METHOD)
                  .withHeader(
                      "Allow",
                      mna.allowed().stream().map(Enum::name).collect(Collectors.joining(", ")));
          default -> {
            LOG.error("Unhandled exception in handler", t);
            yield Response.status(HTTP_INTERNAL_ERROR);
          }
        };
  }

  public static HttpHandler notFoundHandler() {
    return exchange -> {
      try (exchange) {
        exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1);
      }
    };
  }

  /** Returns 204 No Content on GET/HEAD; 405 with {@code Allow: GET, HEAD} otherwise. */
  public static HttpHandler aliveHandler() {
    return new MethodLimitedHandler(
        exchange -> {
          try (exchange) {
            exchange.sendResponseHeaders(HTTP_NO_CONTENT, -1);
          }
        });
  }

  /**
   * Health endpoint handler. Accepts GET and HEAD; returns 200 with {@code application/json} body
   * when the supplied probe reports {@code up == true}, and 503 with the same body shape otherwise.
   * A probe that throws a {@link RuntimeException} or returns {@code null} is mapped to a {@code
   * Down} outcome with an empty dependency list (and 503); the failure is never propagated to the
   * default exception handler.
   *
   * <p>The wire shape is
   *
   * <pre>{@code
   * {"outcome":"Up","dependencies":[{"id":"jdbc","status":"Up"}]}
   * }</pre>
   *
   * <p>Serialisation is delegated to the supplied {@code jsonMapper} — typically the same {@link
   * TypeMapper} the caller registered for {@code application/json} on the server. The handler hands
   * the mapper a record-shaped DTO with the components in the order shown above; any standard JSON
   * library (Gson, Jackson, …) serialises it identically.
   *
   * @param jsonMapper used to encode the wire-shape DTO to bytes
   * @param probe supplier of the current {@link HealthOutcome}
   */
  public static HttpHandler healthHandler(TypeMapper jsonMapper, Supplier<HealthOutcome> probe) {
    Objects.requireNonNull(jsonMapper, "jsonMapper");
    Objects.requireNonNull(probe, "probe");
    return new MethodLimitedHandler(
        exchange -> {
          try (exchange) {
            HealthOutcome outcome;
            try {
              outcome = Objects.requireNonNull(probe.get(), "Health probe returned null");
            } catch (RuntimeException e) {
              LOG.warn("Health probe failed", e);
              outcome = new HealthOutcome(false, List.of());
            }
            byte[] body = jsonMapper.writeTo(toWireShape(outcome));
            int status = outcome.up() ? HTTP_OK : HTTP_UNAVAILABLE;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          }
        });
  }

  private static HealthBody toWireShape(HealthOutcome outcome) {
    return new HealthBody(
        label(outcome.up()),
        outcome.dependencies().stream()
            .map(d -> new DependencyBody(d.id(), label(d.up())))
            .toList());
  }

  private static String label(boolean up) {
    return up ? "Up" : "Down";
  }

  /** Wire-shape DTO for the health endpoint. Component order defines JSON field order. */
  private record HealthBody(String outcome, List<DependencyBody> dependencies) {}

  /** Wire-shape DTO for a single dependency entry. */
  private record DependencyBody(String id, String status) {}

  /**
   * Serves a classpath resource. Content-Type is inferred from the file extension. The resource is
   * loaded eagerly; a missing resource fails immediately with {@link IllegalArgumentException}.
   *
   * @param classpathResource absolute classpath path, e.g. {@code /schemas/v1/openapi.yaml}
   */
  public static HttpHandler specHandler(String classpathResource) {
    return new MethodLimitedHandler(new ClasspathResourceHandler(classpathResource));
  }
}
