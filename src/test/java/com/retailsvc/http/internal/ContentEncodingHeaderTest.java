package com.retailsvc.http.internal;

import static com.retailsvc.http.support.TestCodings.named;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.ContentCoding;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Coded;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Identity;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Unsupported;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentEncodingHeaderTest {

  private static final Map<String, ContentCoding> GZIP_ONLY =
      ContentCodings.of(List.of(), List.of()).decoders();
  private static final RequestCoding GZIPPED = new Coded(GZIP_ONLY.get("gzip"));
  private static final RequestCoding IDENTITY = new Identity();
  private static final RequestCoding UNSUPPORTED = new Unsupported();

  @Test
  void nullHeaderIsIdentity() {
    assertThat(parse(null)).isEqualTo(IDENTITY);
  }

  @Test
  void emptyHeaderIsIdentity() {
    assertThat(parse("   ")).isEqualTo(IDENTITY);
  }

  @Test
  void identityIsNotACoding() {
    assertThat(parse("identity")).isEqualTo(IDENTITY);
  }

  @Test
  void gzipIsGzip() {
    assertThat(parse("gzip")).isEqualTo(GZIPPED);
  }

  @Test
  void xGzipIsGzip() {
    assertThat(parse("x-gzip")).isEqualTo(GZIPPED);
  }

  @Test
  void mixedCaseGzipIsGzip() {
    assertThat(parse("GZip")).isEqualTo(GZIPPED);
  }

  @Test
  void gzipWithIdentityIsGzip() {
    assertThat(parse("identity, gzip")).isEqualTo(GZIPPED);
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertThat(parse("  gzip  ")).isEqualTo(GZIPPED);
  }

  @Test
  void brotliIsUnsupported() {
    assertThat(parse("br")).isEqualTo(UNSUPPORTED);
  }

  @Test
  void deflateIsUnsupported() {
    assertThat(parse("deflate")).isEqualTo(UNSUPPORTED);
  }

  @Test
  void stackedCodingsAreUnsupported() {
    assertThat(parse("gzip, gzip")).isEqualTo(UNSUPPORTED);
    assertThat(parse("gzip, br")).isEqualTo(UNSUPPORTED);
  }

  @Test
  void registeredCodingIsRecognised() {
    ContentCoding zstd = named("zstd");

    RequestCoding coding = ContentEncodingHeader.parse("zstd", decoders(zstd));

    assertThat(coding).isEqualTo(new Coded(zstd));
  }

  @Test
  void stackingARegisteredCodingIsStillUnsupported() {
    assertThat(ContentEncodingHeader.parse("zstd, gzip", decoders(named("zstd"))))
        .isEqualTo(UNSUPPORTED);
  }

  private static RequestCoding parse(String header) {
    return ContentEncodingHeader.parse(header, GZIP_ONLY);
  }

  private static Map<String, ContentCoding> decoders(ContentCoding coding) {
    return ContentCodings.of(List.of(coding), List.of()).decoders();
  }
}
