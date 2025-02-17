package com.retailsvc.http.openapi.exceptions;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class UnsupportedVersionExceptionTest {

  @Test
  void testThrowUnsupportedVersionException() {
    String version = "2.0.0";
    assertThatExceptionOfType(UnsupportedVersionException.class)
        .isThrownBy(
            () -> {
              throw new UnsupportedVersionException(version);
            })
        .withMessage("Version %s is not supported.".formatted(version));
  }
}
