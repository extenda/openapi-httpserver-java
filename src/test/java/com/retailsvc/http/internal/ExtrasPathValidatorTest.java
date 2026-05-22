package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.BadRequestException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ExtrasPathValidatorTest {

  @Test
  void plainPathPasses() {
    URI uri = URI.create("/files/a/b.txt");
    assertThat(ExtrasPathValidator.validateAndDecode(uri)).isEqualTo("/files/a/b.txt");
  }

  @Test
  void dotDotSegmentRejected() {
    assertReject("/files/../etc/passwd");
  }

  @Test
  void singleDotSegmentRejected() {
    assertReject("/files/./x");
  }

  @Test
  void emptySegmentRejected() {
    assertReject("/files//x");
  }

  @Test
  void encodedDotRejected() {
    assertReject("/files/%2e%2e/etc/passwd");
    assertReject("/files/%2E/x");
  }

  @Test
  void doubleEncodedDotRejected() {
    assertReject("/files/%252e%252e/etc/passwd");
  }

  @Test
  void encodedSlashRejected() {
    assertReject("/files/%2fetc/passwd");
    assertReject("/files/%2Fetc/passwd");
  }

  @Test
  void backslashRejected() {
    assertReject("/files/x%5cy");
    assertReject("/files/x%5Cy");
  }

  @Test
  void nulByteRejected() {
    assertReject("/files/x%00.txt");
  }

  @Test
  void controlCharRejected() {
    assertReject("/files/x%0ay");
  }

  @Test
  void doubleEncodedPercentRejected() {
    assertReject("/files/%25xx");
    assertReject("/files/%2500");
  }

  private void assertReject(String raw) {
    URI uri = URI.create(raw);
    assertThatThrownBy(() -> ExtrasPathValidator.validateAndDecode(uri))
        .isInstanceOf(BadRequestException.class);
  }
}
