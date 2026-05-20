package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProblemDetailRendererTest {

  @Test
  void rendersAllFieldsWhenPresent() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "expected string", "/x", "type");
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"expected string\",\"pointer\":\"/x\",\"keyword\":\"type\"}");
  }

  @Test
  void omitsNullPointerAndKeyword() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Unauthorized", 401, "missing token", null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"missing token\"}");
  }

  @Test
  void omitsNullDetail() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Not Found", 404, null, null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
  }

  @Test
  void escapesQuoteAndBackslashInDetail() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Bad Request", 400, "a\"b\\c", null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"a\\\"b\\\\c\"}");
  }

  @Test
  void escapesNamedControlCharsInDetail() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "\b\f\n\r\t", null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"\\b\\f\\n\\r\\t\"}");
  }

  @Test
  void escapesUnnamedControlCharsAsHexUnicode() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Bad Request", 400, "", null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"\\u0001\\u001f\"}");
  }

  @Test
  void passesThroughNonAsciiCharactersVerbatim() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Bad Request", 400, "café-é", null, null);
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad"
                + " Request\",\"status\":400,\"detail\":\"café-é\"}");
  }

  private static String asString(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
