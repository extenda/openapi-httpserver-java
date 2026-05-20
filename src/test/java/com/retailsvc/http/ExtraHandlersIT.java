package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtraHandlersIT extends ServerBaseTest {

  @Test
  // MIGRATED-IN-TASK-6: re-enable Handlers.aliveHandler() once extraRoute accepts RequestHandler
  void aliveExtraReturns204AndBypassesValidation() throws Exception {
    com.sun.net.httpserver.HttpHandler alive =
        ex -> {
          try (ex) {
            ex.sendResponseHeaders(204, -1);
          }
        };
    try (var s =
            newBuilder().spec(spec).handlers(Map.of()).port(0).extraRoute("/alive", alive).build();
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
  // MIGRATED-IN-TASK-6: re-enable Handlers.specHandler() once extraRoute accepts RequestHandler
  void specHandlerServesClasspathResource() throws Exception {
    byte[] yamlBytes = ExtraHandlersIT.class.getResourceAsStream("/openapi.yaml").readAllBytes();
    com.sun.net.httpserver.HttpHandler serveYaml =
        ex -> {
          try (ex) {
            ex.getResponseHeaders().add("Content-Type", "application/yaml");
            ex.sendResponseHeaders(200, yamlBytes.length);
            ex.getResponseBody().write(yamlBytes);
          }
        };
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(Map.of())
                .port(0)
                .extraRoute("/openapi.yaml", serveYaml)
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
  void extraHandlerExceptionFlowsThroughExceptionHandler() throws Exception {
    com.sun.net.httpserver.HttpHandler boom =
        ex -> {
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
