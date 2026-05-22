package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactUrlMatchingIT extends ServerBaseTest {

  @Test
  void extraRouteRejectsTrailingSuffix() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      HttpResponse<String> resp = get(client, s, "/alive232");

      assertThat(resp.statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void extraRouteRejectsSubPath() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      HttpResponse<String> resp = get(client, s, "/alive/34");

      assertThat(resp.statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void extraRouteAcceptsExactPath() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      HttpResponse<String> resp = get(client, s, "/alive");

      assertThat(resp.statusCode()).isEqualTo(204);
    }
  }

  @Test
  void specRouteRejectsTrailingSuffix() throws Exception {
    Map<String, RequestHandler> handlers = Map.of("get-data", req -> Response.status(HTTP_OK));
    try (var s = newBuilder().spec(spec).handlers(stubAllHandlers(handlers)).port(0).build();
        var client = httpClient()) {

      HttpResponse<String> resp = get(client, s, "/api/v1/data232");

      assertThat(resp.statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void specRouteRejectsBasePathSuffix() throws Exception {
    try (var s = newBuilder().spec(spec).handlers(stubAllHandlers(Map.of())).port(0).build();
        var client = httpClient()) {

      HttpResponse<String> resp = get(client, s, "/api/v1xyz/data");

      assertThat(resp.statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  private HttpResponse<String> get(HttpClient client, OpenApiServer s, String path)
      throws Exception {
    var req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + s.listenPort() + path))
            .GET()
            .build();
    return client.send(req, BodyHandlers.ofString());
  }
}
