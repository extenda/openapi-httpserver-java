package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SpecFromInputStreamTest {

  @Test
  void fromJsonLoadsClasspathStreamUsingGson() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
      Spec spec = Spec.fromJson(in);

      assertThat(spec.openapi()).startsWith("3.1");
      assertThat(spec.basePath()).isEqualTo("/api/v1");
      assertThat(spec.operations()).isNotEmpty();
    }
  }

  @Test
  void fromYamlLoadsClasspathStreamUsingSnakeYaml() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/openapi.yaml")) {
      Spec spec = Spec.fromYaml(in);

      assertThat(spec.openapi()).startsWith("3.1");
      assertThat(spec.operations()).isNotEmpty();
    }
  }

  @Test
  void fromJsonWithCustomParserDoesNotRequireGson() throws Exception {
    Gson gson = new Gson();
    Function<byte[], Map<String, Object>> parser =
        bytes -> gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class);

    try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
      Spec spec = Spec.fromJson(in, parser);

      assertThat(spec.openapi()).startsWith("3.1");
    }
  }

  @Test
  void fromYamlWithCustomParserDoesNotRequireSnakeYaml() throws Exception {
    Yaml yaml = new Yaml();
    Function<byte[], Map<String, Object>> parser =
        bytes -> yaml.load(new String(bytes, StandardCharsets.UTF_8));

    try (InputStream in = getClass().getResourceAsStream("/openapi.yaml")) {
      Spec spec = Spec.fromYaml(in, parser);

      assertThat(spec.openapi()).startsWith("3.1");
    }
  }

  @Test
  void fromJsonClosesStream() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);
    try (InputStream raw = getClass().getResourceAsStream("/openapi.json")) {
      InputStream tracking = closingTracker(raw, closed);

      Spec.fromJson(tracking);

      assertThat(closed).isTrue();
    }
  }

  @Test
  void fromYamlClosesStream() throws Exception {
    AtomicBoolean closed = new AtomicBoolean(false);
    try (InputStream raw = getClass().getResourceAsStream("/openapi.yaml")) {
      InputStream tracking = closingTracker(raw, closed);

      Spec.fromYaml(tracking);

      assertThat(closed).isTrue();
    }
  }

  @Test
  void fromJsonWithParserClosesStream() {
    AtomicBoolean closed = new AtomicBoolean(false);
    String json =
        "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
            + "\"servers\":[{\"url\":\"http://localhost/x\"}],\"paths\":{}}";
    InputStream tracking =
        closingTracker(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), closed);
    Gson gson = new Gson();
    Function<byte[], Map<String, Object>> parser =
        bytes -> gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class);

    Spec.fromJson(tracking, parser);

    assertThat(closed).isTrue();
  }

  @Test
  void fromJsonRejectsNullStream() {
    assertThatThrownBy(() -> Spec.fromJson((InputStream) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromYamlRejectsNullStream() {
    assertThatThrownBy(() -> Spec.fromYaml((InputStream) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromJsonWithParserRejectsNullArgs() {
    InputStream in = new ByteArrayInputStream(new byte[0]);
    Function<byte[], Map<String, Object>> parser = bytes -> Map.of();

    assertThatThrownBy(() -> Spec.fromJson(null, parser)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Spec.fromJson(in, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromYamlWithParserRejectsNullArgs() {
    InputStream in = new ByteArrayInputStream(new byte[0]);
    Function<byte[], Map<String, Object>> parser = bytes -> Map.of();

    assertThatThrownBy(() -> Spec.fromYaml(null, parser)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Spec.fromYaml(in, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void fromJsonPropagatesIoFailure() {
    InputStream broken = brokenStream();

    assertThatThrownBy(() -> Spec.fromJson(broken)).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void fromYamlPropagatesIoFailure() {
    InputStream broken = brokenStream();

    assertThatThrownBy(() -> Spec.fromYaml(broken)).isInstanceOf(UncheckedIOException.class);
  }

  private static InputStream closingTracker(InputStream delegate, AtomicBoolean flag) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return delegate.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
      }

      @Override
      public void close() throws IOException {
        flag.set(true);
        delegate.close();
      }
    };
  }

  private static InputStream brokenStream() {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("boom");
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        throw new IOException("boom");
      }
    };
  }
}
