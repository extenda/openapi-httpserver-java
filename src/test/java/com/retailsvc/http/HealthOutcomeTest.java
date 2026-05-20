package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthOutcomeTest {

  @Test
  void exposesUpAndDependencies() {
    HealthOutcome o = new HealthOutcome(true, List.of(new Dependency("jdbc", true)));
    assertThat(o.up()).isTrue();
    assertThat(o.dependencies()).containsExactly(new Dependency("jdbc", true));
  }

  @Test
  void coercesNullDependenciesToEmpty() {
    HealthOutcome o = new HealthOutcome(true, null);
    assertThat(o.dependencies()).isEmpty();
  }

  @Test
  void copiesDependencyListDefensively() {
    List<Dependency> mutable = new ArrayList<>();
    mutable.add(new Dependency("jdbc", true));
    HealthOutcome o = new HealthOutcome(true, mutable);
    mutable.clear();
    assertThat(o.dependencies()).hasSize(1);
  }
}
