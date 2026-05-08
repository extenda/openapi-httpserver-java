package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.retailsvc.http.spec.schema.ObjectSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecTest {
  private final Gson gson = new Gson();

  @SuppressWarnings("unchecked")
  private Map<String, Object> loadJson(String resource) throws Exception {
    String text = new String(SpecTest.class.getResourceAsStream("/" + resource).readAllBytes());
    return (Map<String, Object>) gson.fromJson(text, Map.class);
  }

  @Test
  void parsesMinimalSpec() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "x", "version", "1"),
            "servers", List.of(Map.of("url", "http://localhost/api")),
            "paths", Map.of());
    Spec spec = Spec.from(raw);
    assertThat(spec.openapi()).isEqualTo("3.1.0");
    assertThat(spec.info().title()).isEqualTo("x");
    assertThat(spec.servers()).hasSize(1);
    assertThat(spec.basePath()).isEqualTo("/api");
    assertThat(spec.operations()).isEmpty();
  }

  @Test
  void parsesPathsWithMethods() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "x", "version", "1"),
            "servers", List.of(Map.of("url", "http://localhost")),
            "paths",
                Map.of(
                    "/users",
                    Map.of(
                        "get", Map.of("operationId", "list", "responses", Map.of()),
                        "post", Map.of("operationId", "create", "responses", Map.of()))));
    Spec spec = Spec.from(raw);
    assertThat(spec.operations()).hasSize(2);
    assertThat(spec.operations().stream().map(Operation::operationId))
        .containsExactlyInAnyOrder("list", "create");
  }

  @Test
  void resolvesSchemaRef() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "x", "version", "1"),
            "servers", List.of(Map.of("url", "/")),
            "paths", Map.of(),
            "components", Map.of("schemas", Map.of("User", Map.of("type", "object"))));
    Spec spec = Spec.from(raw);
    assertThat(spec.resolveSchema("#/components/schemas/User")).isInstanceOf(ObjectSchema.class);
  }

  @Test
  void parsesExistingFixture() throws Exception {
    Spec spec = Spec.from(loadJson("openapi.json"));
    assertThat(spec.operations()).isNotEmpty();
  }
}
