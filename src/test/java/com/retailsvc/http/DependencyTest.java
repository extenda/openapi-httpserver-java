package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class DependencyTest {

  @Test
  void holdsIdAndUp() {
    Dependency d = new Dependency("jdbc", true);
    assertThat(d.id()).isEqualTo("jdbc");
    assertThat(d.up()).isTrue();
  }

  @Test
  void rejectsNullId() {
    assertThatNullPointerException()
        .isThrownBy(() -> new Dependency(null, true))
        .withMessageContaining("id");
  }
}
