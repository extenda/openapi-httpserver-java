package com.retailsvc.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.OpenApi.MediaType;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.retailsvc.http.openapi.model.OpenApi.PathItem;
import com.retailsvc.http.openapi.model.OpenApi.RequestBody;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import com.retailsvc.http.openapi.model.RequestBodyMapper;
import com.retailsvc.http.openapi.validation.Validator;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OpenApiValidationFilterTest {

  private HttpExchange exchange;
  private Chain chain;
  private OpenApi specification;
  private RequestBodyMapper bodyMapper;
  private Validator validator;

  @BeforeEach
  void setUp() {
    exchange = mock();
    chain = mock();
    specification = mock();
    bodyMapper = mock();
    validator = mock();
  }

  @Test
  void testInstantiateWithGivenSpecification() {
    var filter = new OpenApiValidationFilter(specification, bodyMapper);
    assertThat(filter).isNotNull();
    assertThat(filter.description()).isEqualTo("OpenAPI filter");
  }

  @Test
  void testDescribeSelf() {
    var filter = new OpenApiValidationFilter(specification, bodyMapper);
    assertThat(filter.description()).isEqualTo("OpenAPI filter");
  }

  @Nested
  class ChainTest {

    Schema schema;
    Operation operation;
    PathItem pathItem;

    @BeforeEach
    void setUp() {
      schema = mock();
      operation = mock();
      pathItem = mock();

      Map<String, PathItem> paths = Map.of("/api/v1", pathItem);
      when(specification.paths()).thenReturn(paths);
      when(specification.stripBasePath(anyString())).thenReturn("/");

      when(pathItem.get()).thenReturn(operation);

      when(bodyMapper.mapFrom(any())).thenReturn(Map.of());

      Map<String, MediaType> content = Map.of("application/json", new MediaType(schema));
      var requestBody = new RequestBody("any description", content, List.of());
      when(operation.requestBody()).thenReturn(requestBody);

      when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
      when(exchange.getRequestMethod()).thenReturn("GET");
      when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
      when(exchange.getRequestHeaders()).thenReturn(Headers.of("content-type", "application/json"));
    }

    @Test
    void shouldCallChainAfterValidations() throws IOException {
      when(validator.validate(any(), any())).thenReturn(true);

      var filter = new OpenApiValidationFilter(specification, bodyMapper, validator);
      filter.doFilter(exchange, chain);

      verify(chain, times(1)).doFilter(exchange);
      verify(exchange, never()).sendResponseHeaders(anyInt(), anyInt());
    }

    @Test
    void shouldFailExchangeAfterBadValidation() throws IOException {
      when(validator.validate(any(), any())).thenReturn(false);

      var filter = new OpenApiValidationFilter(specification, bodyMapper, validator);
      filter.doFilter(exchange, chain);

      verify(chain, never()).doFilter(exchange);
      verify(exchange, times(1)).sendResponseHeaders(400, 0);
    }
  }
}
