package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.OpenApi.Info;
import com.retailsvc.http.openapi.model.OpenApi.Server;
import com.retailsvc.http.openapi.model.RequestBodyMapper;
import com.sun.net.httpserver.HttpHandler;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenApiServerTest {

  Server server = new Server("http://localhost:8080/api");
  ExceptionHandler defaultExceptionHandler = Handlers.defaultExceptionHandler();
  RequestBodyMapper requestBodyMapper =
      new RequestBodyMapper() {
        @Override
        public <T> T mapFrom(byte[] body) {
          return (T) new HashMap<String, Object>();
        }
      };

  @Test
  void shouldStartHttpServerWithValidConfiguration() {
    OpenApi validSpec = testSpecification();
    Map<String, HttpHandler> handlers = Collections.emptyMap();

    OpenApiServer openApiServer =
        assertDoesNotThrow(
            () ->
                new OpenApiServer(validSpec, requestBodyMapper, handlers, defaultExceptionHandler));
    assertDoesNotThrow(openApiServer::close);
  }

  @Test
  void shouldThrowExceptionWhenOpenApiSpecificationIsNull() {
    Map<String, HttpHandler> handlers = Collections.emptyMap();

    assertThatThrownBy(
            () -> new OpenApiServer(null, requestBodyMapper, handlers, defaultExceptionHandler))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("OpenAPI specification must not be null");
  }

  @Test
  void shouldThrowExceptionWhenRequestBodyMapperIsNull() {
    OpenApi validSpec = testSpecification();
    Map<String, HttpHandler> handlers = Collections.emptyMap();

    assertThatThrownBy(() -> new OpenApiServer(validSpec, null, handlers, defaultExceptionHandler))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Request body mapper must not be null");
  }

  @Test
  void shouldThrowExceptionWhenRequestHandlersMapIsNull() {
    OpenApi validSpec = testSpecification();

    assertThatThrownBy(
            () -> new OpenApiServer(validSpec, requestBodyMapper, null, defaultExceptionHandler))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Request handlers must not be null");
  }

  @Test
  void testExceptionIsThrownOnInvalidHttpPort() {
    OpenApi validSpec = testSpecification();

    assertThatThrownBy(
            () ->
                new OpenApiServer(
                    validSpec, requestBodyMapper, Map.of(), defaultExceptionHandler, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private OpenApi testSpecification() {
    return new OpenApi(
        "3.1.0", new Info("API", "1.0"), Collections.singletonList(server), Collections.emptyMap());
  }
}
