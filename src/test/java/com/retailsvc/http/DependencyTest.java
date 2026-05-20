package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DependencyTest {

  @Test
  void holdsIdAndStatus() {
    Dependency d = new Dependency("jdbc", "Up");
    assertThat(d.id()).isEqualTo("jdbc");
    assertThat(d.status()).isEqualTo("Up");
  }

  @Test
  void rejectsNullId() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Dependency(null, "Up"))
        .withMessageContaining("id");
  }

  @Test
  void rejectsNullStatus() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Dependency("jdbc", null))
        .withMessageContaining("status");
  }
}
