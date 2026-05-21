package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecurityHeadersDecoratorTest {

  private final ResponseDecorator decorator = Handlers.securityHeadersDecorator();

  @Test
  void addsBothHeadersWhenMissing() {
    Response decorated = decorator.decorate(null, Response.status(200));

    assertThat(decorated.headers())
        .containsEntry("X-Content-Type-Options", "nosniff")
        .containsEntry("Cross-Origin-Resource-Policy", "same-origin");
  }

  @Test
  void preservesHandlerSuppliedNosniffValue() {
    Response handlerResponse = Response.status(200).withHeader("X-Content-Type-Options", "custom");

    Response decorated = decorator.decorate(null, handlerResponse);

    assertThat(decorated.headers())
        .containsEntry("X-Content-Type-Options", "custom")
        .containsEntry("Cross-Origin-Resource-Policy", "same-origin");
  }

  @Test
  void preservesHandlerSuppliedCorpValue() {
    Response handlerResponse =
        Response.status(200).withHeader("Cross-Origin-Resource-Policy", "cross-origin");

    Response decorated = decorator.decorate(null, handlerResponse);

    assertThat(decorated.headers())
        .containsEntry("X-Content-Type-Options", "nosniff")
        .containsEntry("Cross-Origin-Resource-Policy", "cross-origin");
  }
}
