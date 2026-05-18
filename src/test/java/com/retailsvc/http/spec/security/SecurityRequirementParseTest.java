package com.retailsvc.http.spec.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecurityRequirementParseTest {

  @Test
  void singleRequirementParses() {
    List<Object> raw = List.of(Map.of("bearerAuth", List.of()));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req).containsExactly(new SecurityRequirement(Map.of("bearerAuth", List.of())));
  }

  @Test
  void andGroupParses() {
    List<Object> raw = List.of(Map.of("apiKey", List.of(), "bearer", List.of("admin")));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req).hasSize(1);
    assertThat(req.get(0).schemes())
        .containsEntry("apiKey", List.of())
        .containsEntry("bearer", List.of("admin"));
  }

  @Test
  void orGroupsParse() {
    List<Object> raw = List.of(Map.of("apiKey", List.of()), Map.of("bearer", List.of()));
    List<SecurityRequirement> req = SecuritySchemeParser.parseRequirements(raw);
    assertThat(req).hasSize(2);
  }

  @Test
  void nullReturnsEmptyList() {
    assertThat(SecuritySchemeParser.parseRequirements(null)).isEmpty();
  }
}
