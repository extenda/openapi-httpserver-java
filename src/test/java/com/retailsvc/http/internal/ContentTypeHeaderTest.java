package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentTypeHeaderTest {

  @Test
  void subtypeReturnsBareMediaType() {
    assertThat(ContentTypeHeader.subtype("application/json")).isEqualTo("application/json");
  }

  @Test
  void subtypeStripsParameters() {
    assertThat(ContentTypeHeader.subtype("text/plain; charset=utf-8")).isEqualTo("text/plain");
  }

  @Test
  void subtypeTrimsWhitespace() {
    assertThat(ContentTypeHeader.subtype("  application/json  ")).isEqualTo("application/json");
  }

  @Test
  void subtypeDefaultsToApplicationJsonWhenNull() {
    assertThat(ContentTypeHeader.subtype(null)).isEqualTo("application/json");
  }

  @Test
  void subtypeLowerCasesMediaType() {
    assertThat(ContentTypeHeader.subtype("Application/JSON")).isEqualTo("application/json");
    assertThat(ContentTypeHeader.subtype("Text/Plain; charset=UTF-8")).isEqualTo("text/plain");
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
