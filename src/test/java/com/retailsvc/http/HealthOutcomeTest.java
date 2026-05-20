package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthOutcomeTest {

  @Test
  void exposesOutcomeAndDependencies() {
    HealthOutcome o = new HealthOutcome("Up", List.of(new Dependency("jdbc", "Up")));
    assertThat(o.outcome()).isEqualTo("Up");
    assertThat(o.dependencies()).containsExactly(new Dependency("jdbc", "Up"));
  }

  @Test
  void rejectsNullOutcome() {
    assertThatNullPointerException()
        .isThrownBy(() -> new HealthOutcome(null, List.of()))
        .withMessageContaining("outcome");
  }

  @Test
  void coercesNullDependenciesToEmpty() {
    HealthOutcome o = new HealthOutcome("Up", null);
    assertThat(o.dependencies()).isEmpty();
  }

  @Test
  void copiesDependencyListDefensively() {
    List<Dependency> mutable = new ArrayList<>();
    mutable.add(new Dependency("jdbc", "Up"));
    HealthOutcome o = new HealthOutcome("Up", mutable);
    mutable.clear();
    assertThat(o.dependencies()).hasSize(1);
  }

  @Test
  void isUpReturnsTrueForUpCaseInsensitive() {
    assertThat(new HealthOutcome("Up", List.of()).isUp()).isTrue();
    assertThat(new HealthOutcome("UP", List.of()).isUp()).isTrue();
    assertThat(new HealthOutcome("up", List.of()).isUp()).isTrue();
  }

  @Test
  void isUpReturnsFalseForAnythingElse() {
    assertThat(new HealthOutcome("Down", List.of()).isUp()).isFalse();
    assertThat(new HealthOutcome("", List.of()).isUp()).isFalse();
    assertThat(new HealthOutcome("Degraded", List.of()).isUp()).isFalse();
  }
}
