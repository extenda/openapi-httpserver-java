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

  @Test
  void objectSchemaExtensionsExposeXKeys() {
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
            "components",
            Map.of(
                "schemas",
                Map.of(
                    "Promotion",
                    Map.of("type", "object", "properties", Map.of(), "x-ui-hint", "card"))));
    Spec spec = Spec.from(raw);
    assertThat(spec.componentSchemas().get("Promotion").extensions())
        .containsEntry("x-ui-hint", "card");
  }

  @Test
  void stringSchemaExtensionsExposeXKeys() {
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
            "components",
            Map.of("schemas", Map.of("Code", Map.of("type", "string", "x-format-hint", "slug"))));
    Spec spec = Spec.from(raw);
    assertThat(spec.componentSchemas().get("Code").extensions())
        .containsEntry("x-format-hint", "slug");
  }

  @Test
  void oneOfSchemaExtensionsExposeXKeys() {
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
            "components",
            Map.of(
                "schemas",
                Map.of(
                    "Either",
                    Map.of(
                        "oneOf",
                        List.of(Map.of("type", "string"), Map.of("type", "integer")),
                        "x-discriminator-hint",
                        "kind"))));
    Spec spec = Spec.from(raw);
    assertThat(spec.componentSchemas().get("Either").extensions())
        .containsEntry("x-discriminator-hint", "kind");
  }
}
