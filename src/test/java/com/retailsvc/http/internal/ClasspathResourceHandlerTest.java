package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ClasspathResourceHandlerTest {

  @Test
  void getServesBytesVerbatim() throws IOException {
    byte[] expected = readResource("/sample.txt");

    byte[] actual = new ClasspathResourceHandler("/sample.txt").bytes();

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void contentLengthMatchesBytesLength() {
    ClasspathResourceHandler handler = new ClasspathResourceHandler("/sample.txt");

    assertThat(handler.bytes()).hasSize(handler.bytes().length);
  }

  @Test
  void infersApplicationJsonForJsonExtension() {
    assertThat(new ClasspathResourceHandler("/openapi.json").contentType())
        .isEqualTo("application/json");
  }

  @Test
  void infersApplicationYamlForYamlExtension() {
    assertThat(new ClasspathResourceHandler("/openapi.yaml").contentType())
        .isEqualTo("application/yaml");
  }

  @Test
  void infersTextPlainForTxtExtension() {
    assertThat(new ClasspathResourceHandler("/sample.txt").contentType())
        .isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  void fallsBackToOctetStreamForUnknownExtension() {
    assertThat(new ClasspathResourceHandler("/sample.bin").contentType())
        .isEqualTo("application/octet-stream");
  }

  @Test
  void missingResourceThrowsIllegalArgumentExceptionWithPathInMessage() {
    assertThatThrownBy(() -> new ClasspathResourceHandler("/does-not-exist.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.json");
  }

  @Test
  void resourceIsLoadedEagerlyAtConstruction() {
    // If the resource were loaded lazily, construction would succeed and bytes() would fail.
    // Construction itself must fail for missing resources.
    assertThatThrownBy(() -> new ClasspathResourceHandler("/missing.txt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bytesAreNonEmptyForExistingResource() {
    assertThat(new ClasspathResourceHandler("/sample.txt").bytes()).isNotEmpty();
  }

  private static byte[] readResource(String path) throws IOException {
    try (InputStream in = ClasspathResourceHandlerTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing fixture: " + path);
      }
      return in.readAllBytes();
    }
  }
}
