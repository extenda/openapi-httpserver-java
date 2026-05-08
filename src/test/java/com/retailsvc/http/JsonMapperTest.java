package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonMapperTest {
  @Test
  void usableAsLambda() {
    JsonMapper m = body -> new String(body);
    assertThat(m.mapFrom("hello".getBytes())).isEqualTo("hello");
  }
}
