package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.validate.ValidationError;
import org.junit.jupiter.api.Test;

class ProblemDetailRendererTest {
  @Test
  void rendersExpectedFields() {
    String body =
        ProblemDetailRenderer.render(
            new ValidationError("/email", "format", "string does not match format 'email'", null));
    assertThat(body)
        .contains("\"type\":\"about:blank\"")
        .contains("\"title\":\"Bad Request\"")
        .contains("\"status\":400")
        .contains("\"pointer\":\"/email\"")
        .contains("\"keyword\":\"format\"")
        .contains("\"detail\":\"string does not match format 'email'\"");
  }

  @Test
  void escapesQuotesInDetail() {
    String body =
        ProblemDetailRenderer.render(new ValidationError("/x", "k", "has \"quotes\"", null));
    assertThat(body).contains("\"detail\":\"has \\\"quotes\\\"\"");
  }
}
