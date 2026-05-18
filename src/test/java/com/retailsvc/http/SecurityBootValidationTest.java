package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.Spec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityBootValidationTest {

  private static Map<String, Object> raw(
      Map<String, Object> securitySchemes, List<Object> rootSecurity, List<Object> opSecurity) {
    return Map.of(
        "openapi",
        "3.1.0",
        "info",
        Map.of("title", "T", "version", "1"),
        "servers",
        List.of(Map.of("url", "/v1")),
        "security",
        rootSecurity == null ? List.of() : rootSecurity,
        "components",
        securitySchemes == null ? Map.of() : Map.of("securitySchemes", securitySchemes),
        "paths",
        Map.of(
            "/x",
            Map.of(
                "get",
                opSecurity == null
                    ? Map.of(
                        "operationId",
                        "getX",
                        "responses",
                        Map.of("200", Map.of("description", "ok")))
                    : Map.of(
                        "operationId",
                        "getX",
                        "security",
                        opSecurity,
                        "responses",
                        Map.of("200", Map.of("description", "ok"))))));
  }

  @Test
  void missingValidatorThrows() {
    Map<String, Object> r =
        raw(
            Map.of("bearerAuth", Map.of("type", "http", "scheme", "bearer")),
            List.of(),
            List.of(Map.of("bearerAuth", List.of())));
    Spec spec = Spec.from(r);

    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .handlers(Map.of("getX", req -> Response.ok(Map.of())))
                    .port(0)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bearerAuth");
  }

  @Test
  void unsupportedSchemeThrowsWhenReferenced() {
    Map<String, Object> r =
        raw(
            Map.of("oauth", Map.of("type", "oauth2")),
            List.of(),
            List.of(Map.of("oauth", List.of())));
    Spec spec = Spec.from(r);

    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .handlers(Map.of("getX", req -> Response.ok(Map.of())))
                    .port(0)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsupported");
  }

  @Test
  void unknownSchemeReferenceThrows() {
    Map<String, Object> r =
        raw(
            Map.of(), // no schemes defined
            List.of(),
            List.of(Map.of("missingScheme", List.of())));
    Spec spec = Spec.from(r);

    assertThatThrownBy(
            () ->
                OpenApiServer.builder()
                    .spec(spec)
                    .handlers(Map.of("getX", req -> Response.ok(Map.of())))
                    .port(0)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missingScheme");
  }

  @Test
  void externalAuthSkipsAllChecks() throws Exception {
    Map<String, Object> r =
        raw(
            Map.of("bearerAuth", Map.of("type", "http", "scheme", "bearer")),
            List.of(),
            List.of(Map.of("bearerAuth", List.of())));
    Spec spec = Spec.from(r);

    // No validator registered, but externalAuth → must succeed.
    OpenApiServer server =
        OpenApiServer.builder()
            .spec(spec)
            .useExternalAuthentication()
            .handlers(Map.of("getX", req -> Response.ok(Map.of())))
            .port(0)
            .build();

    assertThat(server).isNotNull();
    server.close();
  }
}
