package com.retailsvc.http.openapi.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OperationIdNotFoundExceptionTest {

  @Test
  void testThrowOperationIdNotFoundException() {
    String method = "GET";
    String path = "/some/path";

    assertThatThrownBy(
            () -> {
              throw new OperationIdNotFoundException(method, path);
            })
        .isInstanceOf(OperationIdNotFoundException.class)
        .hasMessage("No operationId found for GET: /some/path");
  }

  @Test
  void testCorrectMessage() {
    String method = "POST";
    String path = "/example";
    OperationIdNotFoundException exception = new OperationIdNotFoundException(method, path);

    assertThat(exception.getMessage()).isEqualTo("No operationId found for POST: /example");
  }

  @Test
  void testImplementNotFoundClassException() {
    OperationIdNotFoundException exception = new OperationIdNotFoundException("PATCH", "/test");

    assertThat(exception).isInstanceOf(NotFoundClassException.class);
  }
}
