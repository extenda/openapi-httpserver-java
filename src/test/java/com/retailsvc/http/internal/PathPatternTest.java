package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PathPatternTest {

  @Test
  void exactPathHasNoWildcardAndMatchesItself() {
    PathPattern p = PathPattern.compile("/alive");
    assertThat(p.hasWildcard()).isFalse();
    assertThat(p.matches("/alive")).isTrue();
    assertThat(p.matches("/alive/")).isFalse();
    assertThat(p.matches("/alive232")).isFalse();
  }

  @Test
  void singleStarMatchesOneSegment() {
    PathPattern p = PathPattern.compile("/files/*");
    assertThat(p.hasWildcard()).isTrue();
    assertThat(p.matches("/files/a")).isTrue();
    assertThat(p.matches("/files/abc.txt")).isTrue();
    assertThat(p.matches("/files/")).isFalse();
    assertThat(p.matches("/files/a/b")).isFalse();
  }

  @Test
  void doubleStarMatchesAnyDepth() {
    PathPattern p = PathPattern.compile("/files/**");
    assertThat(p.matches("/files/")).isTrue();
    assertThat(p.matches("/files/a")).isTrue();
    assertThat(p.matches("/files/a/b/c")).isTrue();
    assertThat(p.matches("/files")).isFalse();
    assertThat(p.matches("/filesx/a")).isFalse();
  }

  @Test
  void midPathDoubleStarSurroundedByLiterals() {
    PathPattern p = PathPattern.compile("/schemas/**/openapi.yaml");
    assertThat(p.matches("/schemas/a/openapi.yaml")).isTrue();
    assertThat(p.matches("/schemas/a/b/openapi.yaml")).isTrue();
    assertThat(p.matches("/schemas/openapi.yaml")).isFalse();
    assertThat(p.matches("/schemas/a/openapi.yamlx")).isFalse();
  }

  @Test
  void mixedSegmentRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/files/prefix-*.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a whole segment");
  }

  @Test
  void emptySegmentRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/files//a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty segment");
  }

  @Test
  void adjacentDoubleStarsRejected() {
    assertThatThrownBy(() -> PathPattern.compile("/a/**/**/b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("adjacent");
  }

  @Test
  void mustStartWithSlash() {
    assertThatThrownBy(() -> PathPattern.compile("files/*"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must start with '/'");
  }
}
