package com.retailsvc.http.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class ResponseCompressionTest {

  @Test
  void nullContentTypeIsNotCompressible() {
    assertThat(ResponseCompression.isCompressible(null)).isFalse();
  }

  @Test
  void jsonIsCompressible() {
    assertThat(ResponseCompression.isCompressible("application/json")).isTrue();
  }

  @Test
  void problemJsonIsCompressible() {
    assertThat(ResponseCompression.isCompressible("application/problem+json")).isTrue();
  }

  @Test
  void yamlIsCompressible() {
    assertThat(ResponseCompression.isCompressible("application/yaml")).isTrue();
    assertThat(ResponseCompression.isCompressible("application/x-yaml")).isTrue();
  }

  @Test
  void textPlainWithCharsetIsCompressible() {
    assertThat(ResponseCompression.isCompressible("text/plain; charset=utf-8")).isTrue();
  }

  @Test
  void xmlSuffixIsCompressible() {
    assertThat(ResponseCompression.isCompressible("image/svg+xml")).isTrue();
    assertThat(ResponseCompression.isCompressible("application/xml")).isTrue();
  }

  @Test
  void formUrlEncodedIsNotCompressible() {
    assertThat(ResponseCompression.isCompressible("application/octet-stream")).isFalse();
  }

  @Test
  void imagePngIsNotCompressible() {
    assertThat(ResponseCompression.isCompressible("image/png")).isFalse();
  }

  @Test
  void eventStreamIsNotCompressible() {
    assertThat(ResponseCompression.isCompressible("text/event-stream")).isFalse();
  }

  @Test
  void matchIsCaseInsensitive() {
    assertThat(ResponseCompression.isCompressible("Application/JSON")).isTrue();
  }

  @Test
  void gzipRoundTripsBytes() throws IOException {
    byte[] plain = "round trip me".repeat(20).getBytes(UTF_8);

    byte[] compressed = ResponseCompression.encode(new GzipCoding(), plain);

    assertThat(compressed).isNotEqualTo(plain);
    assertThat(gunzip(compressed)).isEqualTo(plain);
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }
}
