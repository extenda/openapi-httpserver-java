package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SpecFromClasspathTest {

  @Test
  void loadsJsonSpecFromClasspath() {
    Spec spec = Spec.fromClasspath(getClass(), "/openapi.json");

    assertThat(spec.openapi()).startsWith("3.1");
    assertThat(spec.basePath()).isEqualTo("/api/v1");
    assertThat(spec.operations()).isNotEmpty();
  }

  @Test
  void loadsYamlSpecFromClasspath() {
    Spec spec = Spec.fromClasspath(getClass(), "/openapi.yaml");

    assertThat(spec.openapi()).startsWith("3.1");
    assertThat(spec.operations()).isNotEmpty();
  }

  @Test
  void loadsYmlSpecFromClasspath() {
    Spec spec = Spec.fromClasspath(getClass(), "/openapi.yml");

    assertThat(spec.openapi()).startsWith("3.1");
    assertThat(spec.operations()).isNotEmpty();
  }

  @Test
  void rejectsMissingResource() {
    assertThatThrownBy(() -> Spec.fromClasspath(getClass(), "/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("classpath resource not found");
  }

  @Test
  void rejectsUnknownExtension() {
    assertThatThrownBy(() -> Spec.fromClasspath(getClass(), "/openapi.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unrecognised OpenAPI spec extension");
  }
}
