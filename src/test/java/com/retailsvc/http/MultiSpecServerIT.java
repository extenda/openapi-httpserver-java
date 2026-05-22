package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.support.SpecFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiSpecServerIT {

  @Test
  void twoBindingsServeTrafficAndUnknownPathReturns404() throws Exception {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

    Map<String, RequestHandler> v1Handlers = handlersFor(v1, req -> Response.ok(Map.of("v", 1)));
    Map<String, RequestHandler> v2Handlers = handlersFor(v2, req -> Response.ok(Map.of("v", 2)));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, v1Handlers)
            .addSpec(v2, v2Handlers)
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      HttpResponse<String> v1Resp = get("http://localhost:" + port + "/api/v1/data");
      HttpResponse<String> v2Resp = get("http://localhost:" + port + "/api/v2/data");
      assertThat(v1Resp.statusCode()).isEqualTo(HTTP_OK);
      assertThat(v1Resp.body()).contains("\"v\":1");
      assertThat(v2Resp.statusCode()).isEqualTo(HTTP_OK);
      assertThat(v2Resp.body()).contains("\"v\":2");
      assertThat(get("http://localhost:" + port + "/nope").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  private static Map<String, RequestHandler> handlersFor(Spec spec, RequestHandler shared) {
    Map<String, RequestHandler> out = new LinkedHashMap<>();
    spec.operations().forEach(op -> out.put(op.operationId(), shared));
    return out;
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
