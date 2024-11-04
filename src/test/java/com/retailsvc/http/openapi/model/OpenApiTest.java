package com.retailsvc.http.openapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.exceptions.NoServersDeclaredException;
import com.retailsvc.http.openapi.exceptions.UnsupportedVersionException;
import com.retailsvc.http.openapi.model.OpenApi.Info;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.retailsvc.http.openapi.model.OpenApi.PathItem;
import com.retailsvc.http.openapi.model.OpenApi.Server;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenApiTest {

  @Mock Function<String, OpenApi> mockFunction;

  Info info = new Info("Test API", "0.0.1-local");

  OpenApi openApi;

  @BeforeEach
  void setUp() {
    Collection<Server> servers = List.of(new Server("https://example.com/api"));
    Operation getOperation = new Operation("get-data", null, Map.of());
    PathItem pathItem = new PathItem(getOperation, null, null, null);
    Map<String, PathItem> paths = Map.of("/test", pathItem);

    openApi = new OpenApi("3.1.0", info, servers, paths);
  }

  @Test
  void testParseSpecification() {
    when(mockFunction.apply("spec")).thenReturn(openApi);
    OpenApi result = OpenApi.parse(mockFunction, "spec");
    assertThat(result).isEqualTo(openApi);
  }

  @Test
  void testBasePath() {
    String basePath = openApi.basePath();
    assertThat(basePath).isEqualTo("/api");
  }

  @Test
  void testGetOperation() {
    Optional<Operation> operation = openApi.getOperation("GET", "/api/test");
    assertThat(operation).isPresent();
    assertThat(operation.get().operationId()).isEqualTo("get-data");
  }

  @Test
  void testGetOperationNotFound() {
    Optional<Operation> operation = openApi.getOperation("POST", "/api/test");
    assertThat(operation).isNotPresent();
  }

  @Test
  void testBasePathThrowsWhenNoServers() {
    OpenApi emptyServerOpenApi = new OpenApi("3.1.0", info, List.of(), Map.of());

    assertThatExceptionOfType(NoServersDeclaredException.class)
        .isThrownBy(emptyServerOpenApi::basePath);
  }

  @Test
  void testOpenApiVersion() {
    assertThatExceptionOfType(UnsupportedVersionException.class)
        .isThrownBy(() -> new OpenApi("3.0.0", info, List.of(), Map.of()));
  }
}
