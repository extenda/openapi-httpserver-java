package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.google.gson.Gson;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.Spec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class ServerBaseTest {

  protected Gson gson = new Gson();

  protected Spec spec;
  protected OpenApiServer server;

  @BeforeEach
  void setUp() {
    spec =
        assertDoesNotThrow(
            () -> {
              try (InputStream in = ServerBaseTest.class.getResourceAsStream("/openapi.json")) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
                return Spec.from(raw);
              }
            });
  }

  @AfterEach
  void tearDown() {
    Optional.ofNullable(server).ifPresent(OpenApiServer::close);
  }

  protected OpenApiServer.Builder newBuilder() {
    return OpenApiServer.builder()
        .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
        .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
        .securityValidator("basicAuth", (req, cred) -> Optional.empty());
  }

  protected OpenApiServer newServer(Map<String, RequestHandler> handlers) {
    server =
        assertDoesNotThrow(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .handlers(stubAllHandlers(handlers))
                    .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
                    .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
                    .securityValidator("basicAuth", (req, cred) -> Optional.empty())
                    .port(0)
                    .build());
    return server;
  }

  /**
   * Returns a handler map covering every operationId declared in {@link #spec}, with the supplied
   * {@code overrides} taking precedence. Tests that exercise only a subset of operations can
   * register handlers for just those, and remaining spec operations get a stub returning 200 so the
   * fail-fast boot validation in {@link OpenApiServer.Builder} stays satisfied.
   */
  protected Map<String, RequestHandler> stubAllHandlers(Map<String, RequestHandler> overrides) {
    return stubAllHandlers(spec, overrides);
  }

  /**
   * Static variant of {@link #stubAllHandlers(Map)} for tests that hold their own {@link Spec}
   * instance and do not extend {@link ServerBaseTest} as a fixture.
   */
  static Map<String, RequestHandler> stubAllHandlers(
      Spec spec, Map<String, RequestHandler> overrides) {
    Map<String, RequestHandler> all = new HashMap<>();
    for (Operation op : spec.operations()) {
      all.put(op.operationId(), req -> Response.status(200));
    }
    all.putAll(overrides);
    return all;
  }

  protected HttpRequest newRequest(
      OpenApiServer server, String path, String method, BodyPublisher body) {
    var headers = Map.of("correlation-id", UUID.randomUUID().toString());
    return newRequest(server, path, method, body, headers);
  }

  protected HttpRequest newRequest(
      OpenApiServer server,
      String path,
      String method,
      BodyPublisher body,
      Map<String, String> headers) {
    headers = Objects.requireNonNullElseGet(headers, Map::of);
    var builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:%d/api/v1%s".formatted(server.listenPort(), path)))
            .method(method, body)
            .headers("Content-Type", "application/json");
    headers.forEach(builder::header);
    return builder.build();
  }

  protected HttpClient httpClient() {
    return HttpClient.newBuilder()
        .executor(newVirtualThreadPerTaskExecutor())
        .version(HTTP_1_1)
        .build();
  }
}
