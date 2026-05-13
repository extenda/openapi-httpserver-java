package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

// TODO Task 9: remove @Disabled once Builder.handlers() accepts Map<String, RequestHandler>
@Disabled("Enabled in Task 9 once handlers() takes RequestHandler")
class RequestResponseGatewayTest extends ServerBaseTest {

  /**
   * Casts a {@code Map<String, RequestHandler>} to the raw {@code Map} type so callers compile
   * against the existing {@code Builder.handlers(Map<String, HttpHandler>)} signature. This cast is
   * safe to write here because the class is {@link Disabled} — it never runs until Task 9 replaces
   * the stub with a real {@code handlers(Map<String, RequestHandler>)} overload.
   */
  @SuppressWarnings("unchecked")
  private static Map asRawHandlers(Map<String, RequestHandler> handlers) {
    return handlers;
  }

  @Test
  void respondJsonWritesBodyAndContentType() throws Exception {
    RequestHandler echo = req -> req.respond(200).json(Map.of("op", req.operationId()));
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(asRawHandlers(Map.of("getRoot", echo, "postData", echo)))
            .port(0)
            .build();
    HttpClient client =
        HttpClient.newBuilder()
            .executor(newVirtualThreadPerTaskExecutor())
            .version(HTTP_1_1)
            .build();
    var resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"n\":1}"))
                .build(),
            ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(resp.body()).contains("\"op\":\"postData\"");
  }

  @Test
  void respondEmptyUses204Style() throws Exception {
    RequestHandler ok = req -> req.respond(204).empty();
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(asRawHandlers(Map.of("getRoot", ok, "postData", ok)))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:%d/api/v1/".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(204);
    assertThat(resp.body()).isEmpty();
  }

  @Test
  void respondStreamUsesChunkedEncoding() throws Exception {
    RequestHandler streamer =
        req -> {
          try (var out = req.respond(200).contentType("text/plain").stream()) {
            out.write("hello ".getBytes());
            out.write("world".getBytes());
          }
        };
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(asRawHandlers(Map.of("getRoot", streamer, "postData", streamer)))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:%d/api/v1/".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).isEqualTo("hello world");
  }
}
