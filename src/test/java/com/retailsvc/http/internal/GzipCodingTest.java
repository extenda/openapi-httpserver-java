package com.retailsvc.http.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class GzipCodingTest {

  private final GzipCoding coding = new GzipCoding();

  @Test
  void flushEmitsEverythingWrittenSoFarInDecodableForm() throws IOException {
    byte[] event = "{\"event\":1}\n".getBytes(UTF_8);
    var sink = new ByteArrayOutputStream();

    var out = coding.encode(sink);
    out.write(event);
    out.flush();

    try (var in = new GZIPInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
      assertThat(in.readNBytes(event.length)).isEqualTo(event);
    }
  }

  @Test
  void closedStreamRoundTrips() throws IOException {
    byte[] body = "{\"event\":1}\n{\"event\":2}\n".getBytes(UTF_8);
    var sink = new ByteArrayOutputStream();

    try (var out = coding.encode(sink)) {
      out.write(body);
      out.flush();
    }

    try (var in = coding.decode(new ByteArrayInputStream(sink.toByteArray()))) {
      assertThat(in.readAllBytes()).isEqualTo(body);
    }
  }
}
