package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonMapperTest {
  @Test
  void usableAsLambda() {
    JsonMapper m = String::new;
    assertThat(m.mapFrom("hello".getBytes())).isEqualTo("hello");
  }
}
