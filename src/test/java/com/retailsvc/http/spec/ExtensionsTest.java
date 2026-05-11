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

  @Test
  void infoExtensionsExposeXKeys() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1", "x-contact-team", "platform"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of());
    Spec spec = Spec.from(raw);
    assertThat(spec.info().extensions()).containsEntry("x-contact-team", "platform");
  }

  @Test
  void operationExtensionsExposeXPermissions() {
    Map<String, Object> raw =
        Map.of(
            "openapi",
            "3.1.0",
            "info",
            Map.of("title", "t", "version", "1"),
            "servers",
            List.of(Map.of("url", "https://example.com")),
            "paths",
            Map.of(
                "/promotions",
                Map.of(
                    "post",
                    Map.of(
                        "operationId",
                        "createPromotion",
                        "x-permissions",
                        List.of("pro.promotion.create"),
                        "responses",
                        Map.of()))));
    Spec spec = Spec.from(raw);
    Operation op = spec.operations().getFirst();
    assertThat(op.extensions()).containsEntry("x-permissions", List.of("pro.promotion.create"));
  }
}
