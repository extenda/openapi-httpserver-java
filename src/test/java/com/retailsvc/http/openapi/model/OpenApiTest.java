package com.retailsvc.http.openapi.model;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.exceptions.NoServersDeclaredException;
import com.retailsvc.http.openapi.exceptions.UnsupportedVersionException;
import com.retailsvc.http.openapi.model.OpenApi.Components;
import com.retailsvc.http.openapi.model.OpenApi.MediaType;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.retailsvc.http.openapi.model.OpenApi.PathItem;
import com.retailsvc.http.openapi.model.OpenApi.RequestBody;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenApiTest {

  Function<String, OpenApi> mockFunction;

  Info info = new Info("Test API", "0.0.1-local");
  Operation head = new Operation("head", null, emptyList(), emptyMap());
  Operation get = new Operation("get-data", null, emptyList(), emptyMap());
  Operation put = new Operation("put", null, emptyList(), emptyMap());
  Operation post = new Operation("post", null, emptyList(), emptyMap());
  Operation delete = new Operation("delete", null, emptyList(), emptyMap());
  Operation connect = new Operation("connect", null, emptyList(), emptyMap());
  Operation options = new Operation("options", null, emptyList(), emptyMap());
  Operation trace = new Operation("trace", null, emptyList(), emptyMap());
  Operation patch = new Operation("patch", null, emptyList(), emptyMap());
  Components components = new Components(emptyMap(), emptyMap());

  OpenApi openApi;

  @BeforeEach
  void setUp() {
    mockFunction = mock();

    Collection<Server> servers = List.of(new Server("https://example.com/api"));
    PathItem pathItem = new PathItem(head, get, put, post, delete, connect, options, trace, patch);
    Map<String, PathItem> paths = Map.of("/test", pathItem);

    openApi = new OpenApi("3.1.0", info, servers, paths, components);
  }

  @ParameterizedTest
  @CsvSource({
    "GET,      /api/test,  get-data,  true",
    "POST,     /api/test,  post,      true",
    "PUT,      /api/test,  put,       true",
    "DELETE,   /api/test,  delete,    true",
    "PATCH,    /api/test,  patch,     true",
    "HEAD,     /api/test,  head,      true",
    "OPTIONS,  /api/test,  options,   true",
    "TRACE,    /api/test,  trace,     true",
    "CONNECT,  /api/test,  connect,   true",
    "GT,       /api/test,  null,      false" // invalid method case
  })
  void testFindOperation(
      String method, String path, String expectedOperationId, boolean shouldBePresent) {
    Optional<Operation> operation = openApi.findOperation(method, path);
    assertThat(operation.isPresent()).isEqualTo(shouldBePresent);
    if (shouldBePresent) {
      assertThat(operation).isPresent();
      assertThat(operation.get().operationId()).isEqualTo(expectedOperationId);
    }
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
  void testBasePathThrowsWhenNoServers() {
    OpenApi emptyServerOpenApi = new OpenApi("3.1.0", info, emptyList(), emptyMap(), components);

    assertThatExceptionOfType(NoServersDeclaredException.class)
        .isThrownBy(emptyServerOpenApi::basePath);
  }

  @Test
  void testOpenApiVersion() {
    List<Server> servers = emptyList();
    Map<String, PathItem> pathItems = emptyMap();

    assertThatExceptionOfType(UnsupportedVersionException.class)
        .isThrownBy(() -> new OpenApi("3.0.0", info, servers, pathItems, components));
  }

  @Test
  void shouldFindResolvedSchemaWhenUsingRef() {
    String $ref = "#/components/schemas/test";

    var schema = new OpenApi.Schema($ref, null, null, null, null, null, null, null);
    Map<String, MediaType> mediaTypes = Map.of("application/json", new MediaType(schema));
    var requestBody = new RequestBody("fictive request body", mediaTypes, emptyList());
    var operation = new Operation("op", requestBody, emptyList(), emptyMap());

    var pathItem = new PathItem(null, operation, null, null, null, null, null, null, null);
    Map<String, PathItem> paths = Map.of("/test", pathItem);

    Schema referencedSchema =
        new Schema("integer", "int32", null, emptyMap(), emptyMap(), emptyList(), null, null);
    Components components = new Components(Map.of("test", referencedSchema), emptyMap());

    var spec = new OpenApi("3.1.0", new Info("test", "0"), emptyList(), paths, components);

    assertThat(referencedSchema)
        .isSameAs(spec.resolveSchema($ref))
        .isSameAs(spec.resolveSchema($ref));
  }
}
