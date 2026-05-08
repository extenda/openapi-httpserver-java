package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpMethodTest {
  @Test
  void parsesUppercase() {
    assertThat(HttpMethod.parse("GET")).isEqualTo(HttpMethod.GET);
  }

  @Test
  void parsesLowercase() {
    assertThat(HttpMethod.parse("get")).isEqualTo(HttpMethod.GET);
  }

  @Test
  void parsesMixed() {
    assertThat(HttpMethod.parse("PaTcH")).isEqualTo(HttpMethod.PATCH);
  }

  @Test
  void unknownThrows() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> HttpMethod.parse("foo"));
  }
}
