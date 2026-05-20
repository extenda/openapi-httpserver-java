package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceSourceTest {

  @Test
  void ofClasspathReportsLengthAtConstruction() throws IOException {
    byte[] expected = readClasspath("/sample.txt");

    ResourceSource source = ResourceSource.ofClasspath("/sample.txt");

    assertThat(source.length()).isEqualTo(expected.length);
  }

  @Test
  void ofClasspathStreamsBytesVerbatim() throws IOException {
    byte[] expected = readClasspath("/sample.txt");

    try (InputStream in = ResourceSource.ofClasspath("/sample.txt").open()) {
      assertThat(in.readAllBytes()).isEqualTo(expected);
    }
  }

  @Test
  void ofClasspathOpenReturnsFreshStreamEachCall() throws IOException {
    ResourceSource source = ResourceSource.ofClasspath("/sample.txt");

    byte[] first;
    byte[] second;
    try (InputStream in = source.open()) {
      first = in.readAllBytes();
    }
    try (InputStream in = source.open()) {
      second = in.readAllBytes();
    }

    assertThat(first).isEqualTo(second).isNotEmpty();
  }

  @Test
  void ofClasspathInfersJsonContentType() {
    assertThat(ResourceSource.ofClasspath("/openapi.json").contentType())
        .isEqualTo("application/json");
  }

  @Test
  void ofClasspathInfersYamlContentType() {
    assertThat(ResourceSource.ofClasspath("/openapi.yaml").contentType())
        .isEqualTo("application/yaml");
  }

  @Test
  void contentTypeForInfersHtml() {
    assertThat(ResourceSource.contentTypeFor("index.html")).isEqualTo("text/html; charset=utf-8");
  }

  @Test
  void contentTypeForInfersCss() {
    assertThat(ResourceSource.contentTypeFor("style.css")).isEqualTo("text/css; charset=utf-8");
  }

  @Test
  void contentTypeForInfersJavascript() {
    assertThat(ResourceSource.contentTypeFor("app.js")).isEqualTo("text/javascript; charset=utf-8");
  }

  @Test
  void contentTypeForFallsBackToOctetStream() {
    assertThat(ResourceSource.contentTypeFor("blob.bin")).isEqualTo("application/octet-stream");
  }

  @Test
  void ofClasspathMissingResourceThrowsWithPathInMessage() {
    assertThatThrownBy(() -> ResourceSource.ofClasspath("/does-not-exist.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.json");
  }

  @Test
  void ofFileReportsLengthAtConstruction(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("data.txt");
    Files.writeString(file, "hello");

    ResourceSource source = ResourceSource.ofFile(file);

    assertThat(source.length()).isEqualTo(5);
  }

  @Test
  void ofFileStreamsBytesVerbatim(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("data.txt");
    Files.writeString(file, "hello");

    try (InputStream in = ResourceSource.ofFile(file).open()) {
      assertThat(new String(in.readAllBytes())).isEqualTo("hello");
    }
  }

  @Test
  void ofFileMissingThrowsWithPathInMessage(@TempDir Path tmp) {
    Path missing = tmp.resolve("nope.txt");

    assertThatThrownBy(() -> ResourceSource.ofFile(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.txt");
  }

  @Test
  void ofFileDirectoryThrows(@TempDir Path tmp) {
    assertThatThrownBy(() -> ResourceSource.ofFile(tmp))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] readClasspath(String path) throws IOException {
    try (InputStream in = ResourceSourceTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing fixture: " + path);
      }
      return in.readAllBytes();
    }
  }
}
