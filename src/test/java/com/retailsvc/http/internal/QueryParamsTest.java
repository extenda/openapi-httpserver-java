package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;

class QueryParamsTest {

  @Test
  void nullOrBlankQueryYieldsEmptyMap() {
    assertThat(QueryParams.parse(null)).isEmpty();
    assertThat(QueryParams.parse("   ")).isEmpty();
  }

  @Test
  void encodedSeparatorStaysInsideValue() {
    // %26 is decoded after splitting, so it does not become a delimiter: one param, not two.
    assertThat(QueryParams.parse("q=a%26b=c")).containsExactly(entry("q", "a&b=c"));
  }

  @Test
  void splitsPairsDecodesAndKeepsFirstOccurrence() {
    assertThat(QueryParams.parse("name=Alice%20Smith&name=Bob&city=x"))
        .containsExactly(entry("name", "Alice Smith"), entry("city", "x"));
  }

  @Test
  void skipsEmptyPairsAndHandlesKeysWithoutValue() {
    // Empty pair (from "&&") is skipped; a key with no '=' maps to an empty value.
    assertThat(QueryParams.parse("a=1&&flag&b=2"))
        .containsExactly(entry("a", "1"), entry("flag", ""), entry("b", "2"));
  }
}
