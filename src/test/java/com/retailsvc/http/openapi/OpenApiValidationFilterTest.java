package com.retailsvc.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.RequestBodyMapper;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenApiValidationFilterTest {

  @Mock HttpExchange exchange;
  @Mock Chain chain;
  @Mock OpenApi openApi;
  @Mock RequestBodyMapper bodyMapper;

  OpenApiValidationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new OpenApiValidationFilter(openApi, bodyMapper);
  }

  @Test
  void testInstantiateWithGivenSpecification() {
    assertThat(filter).isNotNull();
    assertThat(filter.description()).isEqualTo("OpenAPI filter");
  }

  @Test
  void testDescribeSelf() {
    assertThat(filter.description()).isEqualTo("OpenAPI filter");
  }

  @Test
  @Disabled
  void testContinueFiltering() throws IOException {
    when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
    when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
    when(exchange.getRequestHeaders()).thenReturn(Headers.of("content-type", "application/json"));

    filter.doFilter(exchange, chain);
    verify(chain, times(1)).doFilter(exchange);
  }
}
