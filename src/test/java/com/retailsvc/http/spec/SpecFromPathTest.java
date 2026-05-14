package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SpecFromPathTest {

  @Test
  void loadsJsonSpecViaGson() throws Exception {
    Path resource = Path.of(getClass().getResource("/openapi.json").toURI());

    Spec spec = Spec.fromPath(resource);

    assertThat(spec.openapi()).startsWith("3.1");
    assertThat(spec.basePath()).isEqualTo("/api/v1");
    assertThat(spec.operations()).isNotEmpty();
  }

  @Test
  void loadsYamlSpecViaSnakeYaml() throws Exception {
    Path resource = Path.of(getClass().getResource("/openapi.yaml").toURI());

    Spec spec = Spec.fromPath(resource);

    assertThat(spec.openapi()).startsWith("3.1");
    assertThat(spec.operations()).isNotEmpty();
  }

  @Test
  void rejectsUnknownExtension(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
    Path unknown = tmp.resolve("spec.txt");
    java.nio.file.Files.writeString(unknown, "{}");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Spec.fromPath(unknown))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unrecognised OpenAPI spec extension");
  }
}
