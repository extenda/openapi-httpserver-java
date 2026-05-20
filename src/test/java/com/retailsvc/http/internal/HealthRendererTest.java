package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.Dependency;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthRendererTest {

  @Test
  void rendersUpWithNoDependencies() {
    assertThat(HealthRenderer.renderJson(true, List.of()))
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[]}");
  }

  @Test
  void rendersDownWithNoDependencies() {
    assertThat(HealthRenderer.renderJson(false, List.of()))
        .isEqualTo("{\"outcome\":\"Down\",\"dependencies\":[]}");
  }

  @Test
  void rendersSingleDependency() {
    assertThat(HealthRenderer.renderJson(true, List.of(new Dependency("jdbc", true))))
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"jdbc\",\"status\":\"Up\"}]}");
  }

  @Test
  void rendersMultipleDependenciesInOrderWithCommaSeparators() {
    List<Dependency> deps = List.of(new Dependency("jdbc", true), new Dependency("redis", false));
    assertThat(HealthRenderer.renderJson(false, deps))
        .isEqualTo(
            "{\"outcome\":\"Down\",\"dependencies\":["
                + "{\"id\":\"jdbc\",\"status\":\"Up\"},"
                + "{\"id\":\"redis\",\"status\":\"Down\"}]}");
  }

  @Test
  void escapesQuoteAndBackslashInId() {
    assertThat(HealthRenderer.renderJson(true, List.of(new Dependency("a\"b\\c", true))))
        .isEqualTo(
            "{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"a\\\"b\\\\c\",\"status\":\"Up\"}]}");
  }

  @Test
  void escapesNamedControlCharsInId() {
    assertThat(HealthRenderer.renderJson(true, List.of(new Dependency("\b\f\n\r\t", true))))
        .isEqualTo(
            "{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"\\b\\f\\n"
                + "\\r"
                + "\\t\",\"status\":\"Up\"}]}");
  }

  @Test
  void escapesUnnamedControlCharsAsHexUnicode() {
    assertThat(HealthRenderer.renderJson(true, List.of(new Dependency("", true))))
        .isEqualTo(
            "{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"\\u0001\\u001f\",\"status\":\"Up\"}]}");
  }

  @Test
  void passesThroughNonAsciiCharactersVerbatim() {
    assertThat(HealthRenderer.renderJson(true, List.of(new Dependency("café-é", true))))
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"café-é\",\"status\":\"Up\"}]}");
  }
}
