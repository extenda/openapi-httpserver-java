package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.internal.ProblemDetail.Entry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailRendererTest {

  @Test
  void rendersSingleEntryErrorsArray() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank",
            "Bad Request",
            400,
            "expected string",
            List.of(new Entry("#/x", "type", "expected string")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"expected string\",\"errors\":[{\"pointer\":\"#/x\","
                + "\"keyword\":\"type\",\"detail\":\"expected string\"}]}");
  }

  @Test
  void rendersMultipleErrorEntries() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank",
            "Bad Request",
            400,
            "matched 0 of 2 oneOf branches",
            List.of(
                new Entry("#/collar/size", "type", "expected integer"),
                new Entry("#/bark", "type", "expected boolean")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad"
                + " Request\",\"status\":400,\"detail\":\"matched 0 of 2 oneOf"
                + " branches\",\"errors\":[{\"pointer\":\"#/collar/size\",\"keyword\":\"type\",\"detail\":\"expected"
                + " integer\"},{\"pointer\":\"#/bark\",\"keyword\":\"type\",\"detail\":\"expected"
                + " boolean\"}]}");
  }

  @Test
  void omitsKeywordWithinEntryWhenNull() {
    ProblemDetail pd =
        new ProblemDetail(
            "about:blank",
            "Unprocessable Content",
            422,
            "email taken",
            List.of(new Entry("#/email", null, "email taken")));
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Unprocessable Content\",\"status\":422,"
                + "\"detail\":\"email taken\",\"errors\":[{\"pointer\":\"#/email\","
                + "\"detail\":\"email taken\"}]}");
  }

  @Test
  void omitsEmptyErrorsArray() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Unauthorized", 401, "missing token", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"missing token\"}");
  }

  @Test
  void omitsNullDetailAndEmptyErrors() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Not Found", 404, null, List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}");
  }

  @Test
  void escapesQuoteAndBackslashInDetail() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Bad Request", 400, "a\"b\\c", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"a\\\"b\\\\c\"}");
  }

  @Test
  void escapesNamedControlCharsInDetail() {
    ProblemDetail pd =
        new ProblemDetail("about:blank", "Bad Request", 400, "\b\f\n\r\t", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                + "\"detail\":\"\\b\\f\\n\\r\\t\"}");
  }

  @Test
  void passesThroughNonAsciiCharactersVerbatim() {
    ProblemDetail pd = new ProblemDetail("about:blank", "Bad Request", 400, "café-é", List.of());
    assertThat(asString(ProblemDetailRenderer.renderJson(pd)))
        .isEqualTo(
            "{\"type\":\"about:blank\",\"title\":\"Bad"
                + " Request\",\"status\":400,\"detail\":\"café-é\"}");
  }

  private static String asString(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
