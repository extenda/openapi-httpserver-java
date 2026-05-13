package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextPlainParserTest {

  private final TextPlainParser parser = new TextPlainParser();

  @Test
  void decodesUtf8ByDefault() {
    String body = "hello världen";
    assertThat(parser.parse(body.getBytes(StandardCharsets.UTF_8), null)).isEqualTo(body);
  }

  @Test
  void respectsCharsetFromHeader() {
    String body = "räksmörgås";
    byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(bytes, "text/plain; charset=iso-8859-1")).isEqualTo(body);
  }

  @Test
  void emptyBodyDecodesToEmptyString() {
    assertThat(parser.parse(new byte[0], null)).isEmpty();
  }

  @Test
  void unknownCharsetFallsBackToUtf8() {
    String body = "hello";
    assertThat(parser.parse(body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=bogus"))
        .isEqualTo(body);
  }
}
