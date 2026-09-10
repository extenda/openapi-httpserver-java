package com.retailsvc.http.internal;

import static com.retailsvc.http.internal.ContentEncodingHeader.Coding.GZIP;
import static com.retailsvc.http.internal.ContentEncodingHeader.Coding.NONE;
import static com.retailsvc.http.internal.ContentEncodingHeader.Coding.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentEncodingHeaderTest {

  @Test
  void nullHeaderIsNone() {
    assertThat(ContentEncodingHeader.parse(null)).isEqualTo(NONE);
  }

  @Test
  void emptyHeaderIsNone() {
    assertThat(ContentEncodingHeader.parse("   ")).isEqualTo(NONE);
  }

  @Test
  void identityIsNone() {
    assertThat(ContentEncodingHeader.parse("identity")).isEqualTo(NONE);
  }

  @Test
  void gzipIsGzip() {
    assertThat(ContentEncodingHeader.parse("gzip")).isEqualTo(GZIP);
  }

  @Test
  void xGzipIsGzip() {
    assertThat(ContentEncodingHeader.parse("x-gzip")).isEqualTo(GZIP);
  }

  @Test
  void mixedCaseGzipIsGzip() {
    assertThat(ContentEncodingHeader.parse("GZip")).isEqualTo(GZIP);
  }

  @Test
  void gzipWithIdentityIsGzip() {
    assertThat(ContentEncodingHeader.parse("identity, gzip")).isEqualTo(GZIP);
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertThat(ContentEncodingHeader.parse("  gzip  ")).isEqualTo(GZIP);
  }

  @Test
  void brotliIsUnsupported() {
    assertThat(ContentEncodingHeader.parse("br")).isEqualTo(UNSUPPORTED);
  }

  @Test
  void deflateIsUnsupported() {
    assertThat(ContentEncodingHeader.parse("deflate")).isEqualTo(UNSUPPORTED);
  }

  @Test
  void stackedCodingsAreUnsupported() {
    assertThat(ContentEncodingHeader.parse("gzip, gzip")).isEqualTo(UNSUPPORTED);
    assertThat(ContentEncodingHeader.parse("gzip, br")).isEqualTo(UNSUPPORTED);
  }
}
