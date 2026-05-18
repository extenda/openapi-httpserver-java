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

  @Test
  void parsesSecuritySchemesFromComponents() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "paths", Map.of(),
            "components",
                Map.of(
                    "securitySchemes",
                    Map.of(
                        "apiKeyAuth",
                        Map.of("type", "apiKey", "name", "X-API-Key", "in", "header"))));

    Spec spec = Spec.from(raw);

    assertThat(spec.securitySchemes()).containsKey("apiKeyAuth");
  }

  @Test
  void parsesRootSecurity() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "paths", Map.of(),
            "security", List.of(Map.of("bearerAuth", List.of())));

    Spec spec = Spec.from(raw);

    assertThat(spec.security()).hasSize(1);
  }

  @Test
  void securitySchemesDefaultsEmpty() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "paths", Map.of());

    Spec spec = Spec.from(raw);

    assertThat(spec.securitySchemes()).isEmpty();
    assertThat(spec.security()).isEmpty();
  }

  @Test
  void operationLevelSecurityOverridesRoot() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "security", List.of(Map.of("bearerAuth", List.of())),
            "paths",
                Map.of(
                    "/x",
                    Map.of(
                        "get",
                        Map.of(
                            "operationId", "getX",
                            "security", List.of(Map.of("apiKey", List.of())),
                            "responses", Map.of("200", Map.of("description", "ok"))))));

    Spec spec = Spec.from(raw);
    Operation op = spec.operations().getFirst();

    assertThat(op.security()).isPresent();
    assertThat(op.security().get()).hasSize(1);
    assertThat(op.security().get().get(0).schemes()).containsKey("apiKey");
  }

  @Test
  void operationEmptySecurityIsPreserved() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "security", List.of(Map.of("bearerAuth", List.of())),
            "paths",
                Map.of(
                    "/x",
                    Map.of(
                        "get",
                        Map.of(
                            "operationId", "getX",
                            "security", List.of(),
                            "responses", Map.of("200", Map.of("description", "ok"))))));

    Spec spec = Spec.from(raw);
    Operation op = spec.operations().getFirst();

    assertThat(op.security()).isPresent();
    assertThat(op.security().get()).isEmpty();
  }

  @Test
  void operationWithoutSecurityIsEmptyOptional() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "T", "version", "1"),
            "servers", List.of(Map.of("url", "/v1")),
            "paths",
                Map.of(
                    "/x",
                    Map.of(
                        "get",
                        Map.of(
                            "operationId",
                            "getX",
                            "responses",
                            Map.of("200", Map.of("description", "ok"))))));

    Spec spec = Spec.from(raw);
    Operation op = spec.operations().getFirst();

    assertThat(op.security()).isEmpty();
  }
}
