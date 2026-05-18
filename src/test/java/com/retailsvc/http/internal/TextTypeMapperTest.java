package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextTypeMapperTest {

  private final TextTypeMapper mapper = new TextTypeMapper();

  @Test
  void readsUtf8ByDefault() {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    assertThat(mapper.readFrom(body, "text/plain")).isEqualTo("hello");
  }

  @Test
  void readsExplicitCharset() {
    byte[] body = "räksmörgås".getBytes(StandardCharsets.ISO_8859_1);
    assertThat(mapper.readFrom(body, "text/plain; charset=ISO-8859-1")).isEqualTo("räksmörgås");
  }

  @Test
  void writesStringValueAsUtf8() {
    assertThat(mapper.writeTo("ok")).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    assertThat(mapper.writeTo(42)).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
    assertThat(mapper.writeTo(null)).isEqualTo("null".getBytes(StandardCharsets.UTF_8));
  }
}
