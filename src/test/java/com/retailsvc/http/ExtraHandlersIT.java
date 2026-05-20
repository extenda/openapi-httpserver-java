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
  void specHandlerServesClasspathResource() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(Map.of())
                .port(0)
                .extraRoute("/openapi.yaml", Handlers.specHandler("/openapi.yaml"))
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
