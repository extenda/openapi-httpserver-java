package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormTypeMapperTest {

  private final FormTypeMapper mapper = new FormTypeMapper();

  @Test
  void readsKeyValuePairs() {
    byte[] body = "name=Alice&color=blue".getBytes(StandardCharsets.UTF_8);
    Object parsed = mapper.readFrom(body, "application/x-www-form-urlencoded");
    assertThat(parsed).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) parsed;
    assertThat(m).containsEntry("name", "Alice").containsEntry("color", "blue");
  }

  @Test
  void writeToIsUnsupported() {
    assertThatThrownBy(() -> mapper.writeTo(Map.of("k", "v")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
