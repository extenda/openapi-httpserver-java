package com.retailsvc.http;

import static com.retailsvc.http.Handlers.defaultExceptionHandler;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.fail;

import com.google.gson.Gson;
import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.charset.StandardCharsets;
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
    try (InputStream in = ServerBaseTest.class.getResourceAsStream("/openapi.json")) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
      spec = Spec.from(raw);
    } catch (Exception e) {
      fail(e);
    }
  }

  @AfterEach
  void tearDown() {
    Optional.ofNullable(server).ifPresent(OpenApiServer::close);
  }

  protected JsonMapper jsonMapper() {
    return body -> gson.fromJson(new String(body), Object.class);
  }

  protected OpenApiServer newServer(Map<String, HttpHandler> handlers) {
    try {
      server = new OpenApiServer(spec, jsonMapper(), handlers, defaultExceptionHandler(), 0);
      return server;
    } catch (Exception e) {
      fail(e);
    }
    return null;
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
