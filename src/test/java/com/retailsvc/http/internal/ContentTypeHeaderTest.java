package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentTypeHeaderTest {

  @Test
  void mediaTypeReturnsBareValue() {
    assertThat(ContentTypeHeader.mediaType("application/json")).isEqualTo("application/json");
  }

  @Test
  void mediaTypeStripsParameters() {
    assertThat(ContentTypeHeader.mediaType("text/plain; charset=utf-8")).isEqualTo("text/plain");
  }

  @Test
  void mediaTypeTrimsWhitespace() {
    assertThat(ContentTypeHeader.mediaType("  application/json  ")).isEqualTo("application/json");
  }

  @Test
  void mediaTypeDefaultsToApplicationJsonWhenNull() {
    assertThat(ContentTypeHeader.mediaType(null)).isEqualTo("application/json");
  }

  @Test
  void mediaTypeLowerCasesValue() {
    assertThat(ContentTypeHeader.mediaType("Application/JSON")).isEqualTo("application/json");
    assertThat(ContentTypeHeader.mediaType("Text/Plain; charset=UTF-8")).isEqualTo("text/plain");
  }

  @Test
  void parameterReturnsValue() {
    assertThat(ContentTypeHeader.parameter("text/plain; charset=iso-8859-1", "charset"))
        .contains("iso-8859-1");
  }

  @Test
  void parameterUnquotesValue() {
    assertThat(ContentTypeHeader.parameter("text/plain; charset=\"utf-8\"", "charset"))
        .contains("utf-8");
  }

  @Test
  void parameterReturnsEmptyWhenMissing() {
    assertThat(ContentTypeHeader.parameter("text/plain", "charset")).isEmpty();
  }

  @Test
  void parameterNameMatchIsCaseInsensitive() {
    assertThat(ContentTypeHeader.parameter("text/plain; CHARSET=utf-8", "charset"))
        .contains("utf-8");
  }

  @Test
  void parameterReturnsEmptyForNullHeader() {
    assertThat(ContentTypeHeader.parameter(null, "charset")).isEmpty();
  }
}
