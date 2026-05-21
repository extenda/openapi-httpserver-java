package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.Spec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandlerBootValidationTest {

  private static Map<String, Object> twoOpSpec() {
    return Map.of(
        "openapi",
        "3.1.0",
        "info",
        Map.of("title", "T", "version", "1"),
        "servers",
        List.of(Map.of("url", "/v1")),
        "paths",
        Map.of(
            "/a",
            Map.of(
                "get",
                Map.of(
                    "operationId",
                    "getA",
                    "responses",
                    Map.of("200", Map.of("description", "ok")))),
            "/b",
            Map.of(
                "get",
                Map.of(
                    "operationId",
                    "getB",
                    "responses",
                    Map.of("200", Map.of("description", "ok"))))));
  }

  @Test
  void missingHandlerForSpecOperationThrows() {
    Spec spec = Spec.from(twoOpSpec());
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("getA", req -> Response.ok(Map.of())))
            .port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("getB");
  }

  @Test
  void handlerForUnknownOperationThrows() {
    Spec spec = Spec.from(twoOpSpec());
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(
                Map.of(
                    "getA", req -> Response.ok(Map.of()),
                    "getB", req -> Response.ok(Map.of()),
                    "ghost", req -> Response.ok(Map.of())))
            .port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  void exactMatchSucceeds() throws Exception {
    Spec spec = Spec.from(twoOpSpec());
    OpenApiServer server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(
                Map.of(
                    "getA", req -> Response.ok(Map.of()),
                    "getB", req -> Response.ok(Map.of())))
            .port(0)
            .build();

    assertThat(server).isNotNull();
    server.close();
  }
}
