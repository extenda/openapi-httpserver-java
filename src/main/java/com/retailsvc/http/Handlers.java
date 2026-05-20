package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.retailsvc.http.internal.HealthRenderer;
import com.retailsvc.http.internal.ProblemDetail;
import com.retailsvc.http.internal.ResourceSource;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Built-in {@link RequestHandler} and {@link ExceptionHandler} factories. */
public final class Handlers {

  private static final Logger LOG = LoggerFactory.getLogger(Handlers.class);

  private Handlers() {}

  /**
   * Default {@link ExceptionHandler} that maps common library exceptions to RFC 7807 problem
   * responses and falls back to a 500 for anything unrecognised.
   *
   * @param jsonMapper mapper used to serialise problem detail bodies
   * @return the exception handler
   */
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

  /**
   * Liveness handler that returns 204 No Content on GET/HEAD; 405 with {@code Allow: GET, HEAD}
   * otherwise.
   *
   * @return the liveness handler
   */
  public static RequestHandler aliveHandler() {
    return req ->
        switch (req.method()) {
          case GET, HEAD -> Response.empty();
          default -> Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
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
   * @return the health handler
   */
  public static RequestHandler healthHandler(Supplier<HealthOutcome> probe) {
    Objects.requireNonNull(probe, "probe");
    return req -> {
      if (req.method() != GET && req.method() != HEAD) {
        return Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
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
   * @return the spec handler
   */
  public static RequestHandler resourceHandler(String classpathResource) {
    return resourceHandler(ResourceSource.ofClasspath(classpathResource));
  }

  /**
   * Serves a filesystem file as a streaming response. Content-Type is inferred from the file
   * extension. Existence and length are resolved at construction; a missing file fails immediately
   * with {@link IllegalArgumentException}. The file is opened and closed per request.
   *
   * @param file path to the file to serve
   * @return a handler that streams {@code file} on {@code GET}
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
          default -> Response.status(HTTP_BAD_METHOD).withHeader("Allow", "GET, HEAD");
        };
  }
}
