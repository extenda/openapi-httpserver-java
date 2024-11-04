package com.retailsvc.http.openapi.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class NoServersDeclaredExceptionTest {

  @Test
  void testNoServersDeclaredExceptionMessage() {
    assertThatExceptionOfType(NoServersDeclaredException.class)
        .isThrownBy(
            () -> {
              throw new NoServersDeclaredException();
            })
        .withMessage("No server urls found");
  }

  @Test
  void testNoServersDeclaredExceptionType() {
    assertThat(new NoServersDeclaredException()).isInstanceOf(NotFoundClassException.class);
  }
}
