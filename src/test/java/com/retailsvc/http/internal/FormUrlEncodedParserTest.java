package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormUrlEncodedParserTest {

  private final FormUrlEncodedParser parser = new FormUrlEncodedParser();

  @Test
  void emptyBodyReturnsEmptyMap() {
    assertThat(parser.parse(new byte[0], null)).isEmpty();
  }

  @Test
  void singleField() {
    assertThat(parser.parse("a=1".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "1"));
  }

  @Test
  void multipleFields() {
    Map<String, Object> result = parser.parse("a=1&b=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
  }

  @Test
  void repeatedKeyBecomesList() {
    Map<String, Object> result = parser.parse("a=1&a=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", List.of("1", "2")));
  }

  @Test
  void threeRepeatedValues() {
    Map<String, Object> result = parser.parse("x=1&x=2&x=3".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("x", List.of("1", "2", "3")));
  }

  @Test
  void emptyValue() {
    assertThat(parser.parse("a=".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void keyWithoutEquals() {
    assertThat(parser.parse("a".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void percentDecodesKeyAndValue() {
    assertThat(parser.parse("a%20b=c%26d".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a b", "c&d"));
  }

  @Test
  void plusIsSpace() {
    assertThat(parser.parse("a=b+c".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "b c"));
  }

  @Test
  void charsetFromHeader() {
    byte[] iso = "x=räka".getBytes(StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(iso, "application/x-www-form-urlencoded; charset=iso-8859-1"))
        .containsExactly(Map.entry("x", "räka"));
  }
}
