package com.retailsvc.http;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenApiServerTest {

  @Test
  void shouldStartHttpServerWithValidConfiguration() {
    Spec validSpec = testSpec();

    assertDoesNotThrow(
        () -> {
          try (var _ =
              OpenApiServer.builder().spec(validSpec).handlers(emptyMap()).port(0).build()) {
            // also close on exit
          }
        });
  }

  @Test
  void shouldThrowExceptionWhenSpecIsNull() {
    OpenApiServer.Builder builder = OpenApiServer.builder().handlers(emptyMap()).port(0);

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec must not be null");
  }

  @Test
  void shouldThrowExceptionWhenHandlersMapIsNull() {
    OpenApiServer.Builder builder = OpenApiServer.builder().spec(testSpec()).port(0);

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("handlers must not be null");
  }

  @Test
  void testExceptionIsThrownOnInvalidHttpPort() {
    OpenApiServer.Builder builder =
        OpenApiServer.builder().spec(testSpec()).handlers(emptyMap()).port(-1);

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  private Spec testSpec() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "Test API", "version", "1.0"),
            "servers", List.of(Map.of("url", "http://localhost:8080/api")),
            "paths", emptyMap());
    return Spec.from(raw);
  }
}
