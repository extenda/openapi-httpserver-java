package com.retailsvc.http;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiServerBuilderTest {

  private final Spec spec = testSpec();
  private final JsonMapper jsonMapper = body -> new java.util.HashMap<String, Object>();

  @Test
  void buildsWithRequiredFieldsOnly() {
    assertDoesNotThrow(
        () -> {
          try (var _ =
              OpenApiServer.builder()
                  .spec(spec)
                  .jsonMapper(jsonMapper)
                  .handlers(emptyMap())
                  .port(0)
                  .build()) {
            // close on exit
          }
        });
  }

  @Test
  void rejectsDuplicateExtraPathOnSecondAddHandler() {
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .jsonMapper(jsonMapper)
            .handlers(emptyMap())
            .addHandler("/alive", Handlers.aliveHandler());

    assertThatThrownBy(() -> b.addHandler("/alive", Handlers.aliveHandler()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/alive");
  }

  @Test
  void rejectsExtraPathEqualToSpecBasePathAtBuildTime() {
    // testSpec() uses "/api" as the basePath (servers[0].url = http://localhost:8080/api).
    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .jsonMapper(jsonMapper)
                    .handlers(emptyMap())
                    .addHandler("/api", Handlers.aliveHandler())
                    .port(0)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/api");
  }

  @Test
  void rejectsNullSpec() {
    assertThatThrownBy(
            () ->
                OpenApiServer.builder().jsonMapper(jsonMapper).handlers(emptyMap()).port(0).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec");
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
