package com.retailsvc.http.openapi;

import static org.assertj.core.api.Assertions.assertThatException;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.exceptions.MissingOperationHandlerException;
import com.retailsvc.http.openapi.exceptions.NotFoundTypeException;
import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RequestDispatchingHandlerTest {

  @Mock OpenApi specification;
  @Mock HttpHandler mockHandler;
  @Mock HttpExchange exchange;

  RequestDispatchingHandler handler;

  @BeforeEach
  void setUp() {
    handler = new RequestDispatchingHandler(specification, Map.of("test", mockHandler));
  }

  @Test
  void testNullSpecification() {
    assertThatException().isThrownBy(() -> new RequestDispatchingHandler(null, Map.of()));
  }

  @Test
  void testNullRequestHandlers() {
    assertThatException().isThrownBy(() -> new RequestDispatchingHandler(specification, null));
  }

  @Test
  void testInvalidOperationThrows() {
    when(exchange.getRequestMethod()).thenReturn("GET");
    when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
    when(specification.getOperation("GET", "/api/test")).thenReturn(Optional.empty());

    assertThatException()
        .isThrownBy(() -> handler.handle(exchange))
        .isInstanceOf(NotFoundTypeException.class)
        .withMessageContaining("GET", "/api/test");
  }

  @Test
  void testOperationInstancesAreCached() throws Exception {
    when(exchange.getRequestMethod()).thenReturn("GET");
    when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
    when(specification.getOperation("GET", "/api/test"))
        .thenReturn(Optional.of(new Operation("test", null, Map.of())));

    handler.handle(exchange);
    handler.handle(exchange);

    verify(mockHandler, times(2)).handle(exchange);
    verify(specification, atMostOnce()).getOperation("GET", "/api/test");
  }

  @Test
  void testMissingHandlerReturnInternalServerErrorHandler() throws Exception {
    when(exchange.getRequestMethod()).thenReturn("GET");
    when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
    when(specification.getOperation("GET", "/api/test"))
        .thenReturn(Optional.of(new Operation("aa", null, Map.of())));

    assertThatException()
        .isThrownBy(() -> handler.handle(exchange))
        .isInstanceOf(MissingOperationHandlerException.class);

    verify(mockHandler, never()).handle(exchange);
    verify(exchange).sendResponseHeaders(500, 0);
  }
}
