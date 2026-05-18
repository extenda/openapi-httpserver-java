package com.retailsvc.http;

import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TypeMapperRegistrationTest extends ServerBaseTest {

  // Valid body satisfying PostDataRequest schema (requires aList + feelingGood)
  private static final String VALID_POST_BODY = "{\"aList\":[\"x\"],\"feelingGood\":true}";

  @Test
  void gsonFallbackIsAutoRegisteredWhenNoJsonMapperConfigured() throws Exception {
    RequestHandler echo =
        req ->
            Response.bytes(
                200,
                gson.toJson(req.parsed()).getBytes(StandardCharsets.UTF_8),
                "application/json");
    server =
        newBuilder()
            .spec(spec)
            .handlers(Map.of("get-data", echo, "post-data", echo))
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
                .POST(BodyPublishers.ofString(VALID_POST_BODY))
                .build(),
            ofString());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body()).contains("\"aList\"");
  }

  @Test
  void userSuppliedMapperOverridesDefault() throws Exception {
    AtomicBoolean readFromCalled = new AtomicBoolean();
    TypeMapper marker =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] b, String h) {
            readFromCalled.set(true);
            return Map.of("aList", List.of("x"), "feelingGood", true);
          }

          @Override
          public byte[] writeTo(Object v) {
            return "ignored".getBytes(StandardCharsets.UTF_8);
          }
        };
    RequestHandler echo = req -> Response.status(200);
    server =
        newBuilder()
            .spec(spec)
            .bodyMapper("application/json", marker)
            .handlers(Map.of("get-data", echo, "post-data", echo))
            .port(0)
            .build();
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"anything\":\"goes\"}"))
                .build(),
            ofString());

    assertThat(readFromCalled).isTrue();
  }

  @Test
  void jsonMapperShortcutRegistersUnderApplicationJson() throws Exception {
    AtomicBoolean readFromCalled = new AtomicBoolean();
    TypeMapper marker =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] b, String h) {
            readFromCalled.set(true);
            return Map.of("aList", List.of("x"), "feelingGood", true);
          }

          @Override
          public byte[] writeTo(Object v) {
            return new byte[0];
          }
        };
    RequestHandler echo = req -> Response.status(200);
    server =
        newBuilder()
            .spec(spec)
            .jsonMapper(marker)
            .handlers(Map.of("get-data", echo, "post-data", echo))
            .port(0)
            .build();
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"anything\":\"goes\"}"))
                .build(),
            ofString());

    assertThat(readFromCalled).isTrue();
  }

  @Test
  void jsonMapperRejectsNullMapper() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    assertThatThrownBy(() -> b.jsonMapper(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void bodyMapperRejectsNullArgs() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    TypeMapper anyMapper = new GsonOnlyMapper();

    assertThatThrownBy(() -> b.bodyMapper(null, anyMapper))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> b.bodyMapper("application/json", null))
        .isInstanceOf(NullPointerException.class);
  }

  private static final class GsonOnlyMapper implements TypeMapper {
    @Override
    public Object readFrom(byte[] b, String h) {
      return null;
    }

    @Override
    public byte[] writeTo(Object v) {
      return new byte[0];
    }
  }
}
