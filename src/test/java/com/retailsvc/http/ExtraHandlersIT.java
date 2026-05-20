package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtraHandlersIT extends ServerBaseTest {

  @Test
  void aliveExtraReturns204AndBypassesValidation() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(Map.of())
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/alive"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(204);
      assertThat(resp.body()).isEmpty();
    }
  }

  @Test
  void resourceHandlerServesClasspathResource() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(Map.of())
                .port(0)
                .extraRoute("/openapi.yaml", Handlers.resourceHandler("/openapi.yaml"))
                .build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/openapi.yaml"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type")).contains("application/yaml");
      assertThat(resp.body()).isNotEmpty();
    }
  }

  @Test
  void textHtmlResponseIsSerializedByDefaultMapper() throws Exception {
    RequestHandler html =
        req -> Response.of(200, "<h1>hi</h1>").withContentType("text/html; charset=UTF-8");

    try (var s =
            newBuilder().spec(spec).handlers(Map.of()).port(0).extraRoute("/page", html).build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/page"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type"))
          .hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
      assertThat(resp.body()).isEqualTo("<h1>hi</h1>");
    }
  }

  @Test
  void extraHandlerExceptionFlowsThroughExceptionHandler() throws Exception {
    RequestHandler boom =
        req -> {
          throw new RuntimeException("boom");
        };

    try (var s =
            newBuilder().spec(spec).handlers(Map.of()).port(0).extraRoute("/boom", boom).build();
        var client = httpClient()) {

      var req =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:" + s.listenPort() + "/boom"))
              .GET()
              .build();
      var resp = client.send(req, BodyHandlers.ofString());

      assertThat(resp.statusCode()).isEqualTo(500);
    }
  }
}
