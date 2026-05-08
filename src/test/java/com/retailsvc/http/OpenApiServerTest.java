package com.retailsvc.http;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenApiServerTest {

  ExceptionHandler onError = Handlers.defaultExceptionHandler();
  JsonMapper jsonMapper = body -> new java.util.HashMap<String, Object>();

  @Test
  void shouldStartHttpServerWithValidConfiguration() {
    Spec validSpec = testSpec();
    Map<String, HttpHandler> handlers = emptyMap();

    assertDoesNotThrow(
        () -> {
          try (var _ = new OpenApiServer(validSpec, jsonMapper, handlers, onError, 0)) {
            // also close on exit
          }
        });
  }

  @Test
  void shouldThrowExceptionWhenSpecIsNull() {
    Map<String, HttpHandler> handlers = emptyMap();

    assertThatThrownBy(() -> new OpenApiServer(null, jsonMapper, handlers, onError))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec must not be null");
  }

  @Test
  void shouldThrowExceptionWhenJsonMapperIsNull() {
    Spec validSpec = testSpec();
    Map<String, HttpHandler> handlers = emptyMap();

    assertThatThrownBy(() -> new OpenApiServer(validSpec, null, handlers, onError))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("JsonMapper must not be null");
  }

  @Test
  void shouldThrowExceptionWhenHandlersMapIsNull() {
    Spec validSpec = testSpec();

    assertThatThrownBy(() -> new OpenApiServer(validSpec, jsonMapper, null, onError))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("handlers must not be null");
  }

  @Test
  void testExceptionIsThrownOnInvalidHttpPort() {
    Spec validSpec = testSpec();
    Map<String, HttpHandler> handlers = emptyMap();
    assertThatThrownBy(() -> new OpenApiServer(validSpec, jsonMapper, handlers, onError, -1))
        .isInstanceOf(IllegalArgumentException.class);
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
