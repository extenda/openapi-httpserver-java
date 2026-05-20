package com.retailsvc.http;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiServerBuilderTest {

  private final Spec spec = testSpec();

  @Test
  void buildsWithRequiredFieldsOnly() {
    assertDoesNotThrow(
        () -> {
          try (var _ = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build()) {
            // close on exit
          }
        });
  }

  @Test
  void rejectsDuplicateExtraPathOnSecondAddHandler() {
    // MIGRATED-IN-TASK-6: replace stub with Handlers.aliveHandler() once extraRoute accepts
    // RequestHandler
    HttpHandler duplicate = exchange -> {};
    OpenApiServer.Builder b =
        OpenApiServer.builder().spec(spec).handlers(emptyMap()).extraRoute("/alive", duplicate);

    assertThatThrownBy(() -> b.extraRoute("/alive", duplicate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/alive");
  }

  @Test
  void rejectsExtraPathEqualToSpecBasePathAtBuildTime() {
    // testSpec() uses "/api" as the basePath (servers[0].url = http://localhost:8080/api).
    // MIGRATED-IN-TASK-6: replace stub with Handlers.aliveHandler() once extraRoute accepts
    // RequestHandler
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(emptyMap())
            .extraRoute("/api", exchange -> {})
            .port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/api");
  }

  @Test
  void rejectsNegativeShutdownTimeout() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThatThrownBy(() -> b.shutdownTimeoutSeconds(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-1");
  }

  @Test
  void buildsWithShutdownTimeout() {
    assertDoesNotThrow(
        () -> {
          try (var _ =
              OpenApiServer.builder()
                  .spec(spec)
                  .handlers(emptyMap())
                  .port(0)
                  .shutdownTimeoutSeconds(2)
                  .build()) {
            // close on exit drains for up to 2s (no in-flight exchanges, so returns immediately)
          }
        });
  }

  @Test
  void stopRejectsNegativeDelay() throws Exception {
    try (var s = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build()) {

      assertThatThrownBy(() -> s.stop(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("-1");
    }
  }

  @Test
  void stopWithZeroSucceeds() throws Exception {
    var s = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build();
    assertDoesNotThrow(() -> s.stop(0));
  }

  @Test
  void rejectsNullSpec() {
    OpenApiServer.Builder b = OpenApiServer.builder().handlers(emptyMap()).port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec");
  }

  @Test
  void bodyMapperRejectsNullMediaType() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    TypeMapper noopMapper =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] body, String contentTypeHeader) {
            return null;
          }

          @Override
          public byte[] writeTo(Object value) {
            return new byte[0];
          }
        };
    assertThatThrownBy(() -> b.bodyMapper(null, noopMapper))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bodyMapperRejectsNullMapper() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    assertThatThrownBy(() -> b.bodyMapper("application/json", null))
        .isInstanceOf(NullPointerException.class);
  }

  private static Spec testSpec() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "Test API", "version", "1.0"),
            "servers", List.of(Map.of("url", "http://localhost:8080/api")),
            "paths", emptyMap());
    return Spec.from(raw);
  }
}
