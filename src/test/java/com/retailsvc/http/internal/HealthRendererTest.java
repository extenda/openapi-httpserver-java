package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailsvc.http.Dependency;
import com.retailsvc.http.HealthOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthRendererTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void rendersOutcomeAndEmptyDependencies() {
    String json = HealthRenderer.toJson(new HealthOutcome("Up", List.of()));
    assertThat(json).isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[]}");
  }

  @Test
  void rendersOutcomeAndDependencies() throws Exception {
    String json =
        HealthRenderer.toJson(
            new HealthOutcome(
                "Down", List.of(new Dependency("jdbc", "Down"), new Dependency("cache", "Up"))));

    JsonNode root = MAPPER.readTree(json);
    assertThat(root.get("outcome").asText()).isEqualTo("Down");
    assertThat(root.get("dependencies")).hasSize(2);
    assertThat(root.get("dependencies").get(0).get("id").asText()).isEqualTo("jdbc");
    assertThat(root.get("dependencies").get(0).get("status").asText()).isEqualTo("Down");
    assertThat(root.get("dependencies").get(1).get("id").asText()).isEqualTo("cache");
    assertThat(root.get("dependencies").get(1).get("status").asText()).isEqualTo("Up");
  }

  @Test
  void escapesQuotesAndBackslashes() throws Exception {
    String json =
        HealthRenderer.toJson(new HealthOutcome("Up", List.of(new Dependency("a\"b\\c", "Up"))));
    JsonNode root = MAPPER.readTree(json);
    assertThat(root.get("dependencies").get(0).get("id").asText()).isEqualTo("a\"b\\c");
  }

  @Test
  void escapesControlCharacters() throws Exception {
    String id = "tab\there\nnextend";
    String json = HealthRenderer.toJson(new HealthOutcome("Up", List.of(new Dependency(id, "Up"))));
    JsonNode root = MAPPER.readTree(json);
    assertThat(root.get("dependencies").get(0).get("id").asText()).isEqualTo(id);
  }
}
