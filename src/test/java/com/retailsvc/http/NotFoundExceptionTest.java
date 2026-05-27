package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotFoundExceptionTest {

  @Test
  void carriesMessage() {
    NotFoundException e = new NotFoundException("missing");

    assertThat(e.getMessage()).isEqualTo("missing");
    assertThat(e.getCause()).isNull();
  }

  @Test
  void preservesCause() {
    Throwable cause = new IllegalStateException("root");

    NotFoundException e = new NotFoundException("missing", cause);

    assertThat(e.getMessage()).isEqualTo("missing");
    assertThat(e).hasCause(cause);
  }
}
