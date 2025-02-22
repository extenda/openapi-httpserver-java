package com.retailsvc.http.openapi;

import static org.assertj.core.api.Assertions.assertThatException;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestDispatchingHandlerTest {

  private OpenApi specification;
  private HttpHandler mockHandler;
  private HttpExchange exchange;

  RequestDispatchingHandler handler;

  @BeforeEach
  void setUp() {
    specification = mock();
    mockHandler = mock();
    exchange = mock();

    handler = new RequestDispatchingHandler(Map.of("test", mockHandler));
  }

  @Test
  void testNullRequestHandlers() {
    assertThatException().isThrownBy(() -> new RequestDispatchingHandler(null));
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
    when(exchange.getAttribute("operation-id")).thenReturn("test");

    when(specification.getOperation("GET", "/api/test"))
        .thenReturn(Optional.of(new Operation("test", null, List.of(), Map.of())));

    handler.handle(exchange);
    handler.handle(exchange);

    verify(mockHandler, times(2)).handle(exchange);
    verify(specification, atMostOnce()).getOperation("GET", "/api/test");
  }

  @Test
  void testMissingHandlerReturnInternalServerErrorHandler() throws Exception {
    when(exchange.getRequestMethod()).thenReturn("GET");
    when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
    when(exchange.getAttribute("operation-id")).thenReturn("not-present-id");
    when(specification.getOperation("GET", "/api/test"))
        .thenReturn(Optional.of(new Operation("aa", null, List.of(), Map.of())));

    assertThatException()
        .isThrownBy(() -> handler.handle(exchange))
        .isInstanceOf(MissingOperationHandlerException.class);

    verify(mockHandler, never()).handle(exchange);
  }
}
