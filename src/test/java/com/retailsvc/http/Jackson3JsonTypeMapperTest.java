package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class Jackson3JsonTypeMapperTest {

  private final Jackson3JsonTypeMapper mapper = new Jackson3JsonTypeMapper(new ObjectMapper());

  @Test
  void readsJsonObjectAsMap() {
    byte[] body = "{\"n\":42,\"s\":\"hi\",\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8);

    Object parsed = mapper.readFrom(body, "application/json");

    assertThat(parsed).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) parsed;
    assertThat(m).containsEntry("n", 42).containsEntry("s", "hi").containsEntry("a", List.of(1, 2));
  }

  @Test
  void writesMapAsJson() {
    byte[] out = mapper.writeTo(Map.of("k", "v"));

    assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("{\"k\":\"v\"}");
  }

  @Test
  void readFailurePropagatesAsJacksonException() {
    byte[] malformed = "not json".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> mapper.readFrom(malformed, "application/json"))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new Jackson3JsonTypeMapper(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mapper");
  }
}
