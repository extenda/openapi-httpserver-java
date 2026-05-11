package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtensionsTest {

  @Test
  void specExtensionsExposeTopLevelXKeys() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of(),
            "x-vendor-build",
            "abc");
    Spec spec = Spec.from(raw);
    assertThat(spec.extensions()).containsEntry("x-vendor-build", "abc");
  }

  @Test
  void specExtensionsEmptyWhenNoXKeys() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of());
    Spec spec = Spec.from(raw);
    assertThat(spec.extensions()).isEmpty();
  }
}
