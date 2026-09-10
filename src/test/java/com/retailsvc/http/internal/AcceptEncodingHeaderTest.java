package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AcceptEncodingHeaderTest {

  @Test
  void nullHeaderIsNotAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip(null)).isFalse();
  }

  @Test
  void blankHeaderIsNotAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("   ")).isFalse();
  }

  @Test
  void plainGzipIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip")).isTrue();
  }

  @Test
  void gzipAmongOtherCodingsIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("br, deflate, gzip")).isTrue();
  }

  @Test
  void caseInsensitiveGzipIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("GZip")).isTrue();
  }

  @Test
  void xGzipIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("x-gzip")).isTrue();
  }

  @Test
  void explicitZeroQValueIsRefused() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip;q=0")).isFalse();
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip;q=0.0")).isFalse();
  }

  @Test
  void positiveQValueIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip;q=0.5")).isTrue();
  }

  @Test
  void wildcardIsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("*")).isTrue();
  }

  @Test
  void wildcardWithZeroQValueIsRefused() {
    assertThat(AcceptEncodingHeader.acceptsGzip("*;q=0")).isFalse();
  }

  @Test
  void explicitGzipBeatsWildcardRefusal() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip, *;q=0")).isTrue();
  }

  @Test
  void explicitGzipRefusalBeatsWildcard() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip;q=0, *")).isFalse();
  }

  @Test
  void identityOnlyIsNotAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("identity")).isFalse();
  }

  @Test
  void deflateOnlyIsNotAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("deflate, br")).isFalse();
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertThat(AcceptEncodingHeader.acceptsGzip("  deflate ,  gzip ; q=0.8 ")).isTrue();
  }

  @Test
  void malformedQValueIsTreatedAsAccepted() {
    assertThat(AcceptEncodingHeader.acceptsGzip("gzip;q=bogus")).isTrue();
  }

  @Test
  void emptyTokensAreIgnored() {
    assertThat(AcceptEncodingHeader.acceptsGzip("deflate,,gzip")).isTrue();
  }
}
