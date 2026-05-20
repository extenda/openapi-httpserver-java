package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthOutcomeTest {

  @Test
  void exposesDependencies() {
    HealthOutcome o = new HealthOutcome(List.of(new Dependency("jdbc", true)));
    assertThat(o.dependencies()).containsExactly(new Dependency("jdbc", true));
  }

  @Test
  void coercesNullDependenciesToEmpty() {
    HealthOutcome o = new HealthOutcome(null);
    assertThat(o.dependencies()).isEmpty();
  }

  @Test
  void copiesDependencyListDefensively() {
    List<Dependency> mutable = new ArrayList<>();
    mutable.add(new Dependency("jdbc", true));
    HealthOutcome o = new HealthOutcome(mutable);
    mutable.clear();
    assertThat(o.dependencies()).hasSize(1);
  }

  @Test
  void emptyDependenciesIsUp() {
    assertThat(new HealthOutcome(List.of()).up()).isTrue();
  }

  @Test
  void upWhenAllDependenciesUp() {
    HealthOutcome o =
        new HealthOutcome(List.of(new Dependency("jdbc", true), new Dependency("redis", true)));
    assertThat(o.up()).isTrue();
  }

  @Test
  void downWhenAnyDependencyDown() {
    HealthOutcome o =
        new HealthOutcome(List.of(new Dependency("jdbc", true), new Dependency("redis", false)));
    assertThat(o.up()).isFalse();
  }
}
