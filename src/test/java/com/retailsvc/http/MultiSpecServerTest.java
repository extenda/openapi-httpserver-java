package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.support.SpecFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class MultiSpecServerTest {

  @Test
  void servesTwoBindingsOnDistinctBasePaths() throws Exception {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

    RequestHandler v1Handler = req -> Response.ok(Map.of("version", "v1"));
    RequestHandler v2Handler = req -> Response.ok(Map.of("version", "v2"));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, handlersFor(v1, v1Handler))
            .addSpec(v2, handlersFor(v2, v2Handler))
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      HttpResponse<String> v1Resp = get("http://localhost:" + port + "/api/v1/data");
      HttpResponse<String> v2Resp = get("http://localhost:" + port + "/api/v2/data");
      assertThat(v1Resp.statusCode()).isEqualTo(HTTP_OK);
      assertThat(v1Resp.body()).contains("v1");
      assertThat(v2Resp.statusCode()).isEqualTo(HTTP_OK);
      assertThat(v2Resp.body()).contains("v2");
    }
  }

  @Test
  void identicalOperationIdsAcrossBindingsDispatchIndependently() throws Exception {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Spec v2 = SpecFixtures.specAt("http://localhost/api/v2");

    AtomicInteger v1Hits = new AtomicInteger();
    AtomicInteger v2Hits = new AtomicInteger();

    Map<String, RequestHandler> v1Handlers =
        handlersFor(
            v1,
            req -> {
              v1Hits.incrementAndGet();
              return Response.ok(Map.of("v", 1));
            });
    Map<String, RequestHandler> v2Handlers =
        handlersFor(
            v2,
            req -> {
              v2Hits.incrementAndGet();
              return Response.ok(Map.of("v", 2));
            });

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, v1Handlers)
            .addSpec(v2, v2Handlers)
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      get("http://localhost:" + port + "/api/v1/data");
      get("http://localhost:" + port + "/api/v2/data");

      assertThat(v1Hits.get()).isEqualTo(1);
      assertThat(v2Hits.get()).isEqualTo(1);
    }
  }

  @Test
  void rejectsTwoBindingsWithSameBasePath() {
    Spec a = SpecFixtures.specAt("http://localhost/api/v1");
    Spec b = SpecFixtures.specAt("http://localhost/api/v1");
    Map<String, RequestHandler> handlersA = handlersFor(a, req -> Response.ok(Map.of()));
    Map<String, RequestHandler> handlersB = handlersFor(b, req -> Response.ok(Map.of()));

    OpenApiServer.Builder builder =
        OpenApiServer.builder()
            .port(0)
            .addSpec(a, handlersA)
            .addSpec(b, handlersB)
            .useExternalAuthentication();

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate basePath")
        .hasMessageContaining("/api/v1");
  }

  @Test
  void missingValidatorOnOneBindingFailsIndependently() {
    Spec v1 = SpecFixtures.specAt("http://localhost/api/v1");
    Assumptions.assumeTrue(
        !v1.securitySchemes().isEmpty(), "test spec has no security schemes to exercise");

    Map<String, RequestHandler> handlers = handlersFor(v1, req -> Response.ok(Map.of()));

    OpenApiServer.Builder builder = OpenApiServer.builder().port(0).addSpec(v1, handlers, Map.of());

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no SchemeValidator registered for security scheme");
  }

  @Test
  void rejectsMixingLegacySpecMethodsWithAddSpec() {
    Spec a = SpecFixtures.specAt("http://localhost/api/v1");
    Spec b = SpecFixtures.specAt("http://localhost/api/v2");
    Map<String, RequestHandler> handlersA = handlersFor(a, req -> Response.ok(Map.of()));
    Map<String, RequestHandler> handlersB = handlersFor(b, req -> Response.ok(Map.of()));

    OpenApiServer.Builder builder =
        OpenApiServer.builder()
            .port(0)
            .spec(a)
            .handlers(handlersA)
            .addSpec(b, handlersB)
            .useExternalAuthentication();

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("use either spec()/handler()/securityValidator() or addSpec()");
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
