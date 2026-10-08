package com.retailsvc.http.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class DecodingInputStreamTest {

  private static final GzipCoding GZIP = new GzipCoding();

  @Test
  void decodesAsItIsRead() throws IOException {
    try (InputStream in = decoding(gzip("hello".getBytes(UTF_8)), 1024)) {
      assertThat(new String(in.readAllBytes(), UTF_8)).isEqualTo("hello");
    }
  }

  @Test
  void singleByteReadsDecodeToo() throws IOException {
    try (InputStream in = decoding(gzip(new byte[] {(byte) 0xFF, 1}), 1024)) {
      assertThat(in.read()).isEqualTo(0xFF);
      assertThat(in.read()).isEqualTo(1);
      assertThat(in.read()).isEqualTo(-1);
    }
  }

  @Test
  void emptyBodyReadsAsEmptyWithoutOpeningTheDecoder() throws IOException {
    try (InputStream in = decoding(new byte[0], 1024)) {
      assertThat(in.read()).isEqualTo(-1);
      assertThat(in.read(new byte[8], 0, 8)).isEqualTo(-1);
    }
  }

  @Test
  void zeroLengthReadDoesNotTouchTheBody() throws IOException {
    try (InputStream in = decoding("not gzip".getBytes(UTF_8), 1024)) {
      assertThat(in.read(new byte[8], 0, 0)).isZero();
    }
  }

  @Test
  void bodyAtTheCapIsAllowed() throws IOException {
    try (InputStream in = decoding(gzip(new byte[1024]), 1024)) {
      assertThat(in.readAllBytes()).hasSize(1024);
    }
  }

  @Test
  void bodyOverTheCapFailsWith413() throws IOException {
    try (InputStream in = decoding(gzip(new byte[1025]), 1024)) {
      assertThatThrownBy(in::readAllBytes)
          .isInstanceOfSatisfying(
              BadRequestException.class, e -> assertThat(e.status()).isEqualTo(413));
    }
  }

  @Test
  void malformedBodyFailsWith400() throws IOException {
    try (InputStream in = decoding("not gzip at all".getBytes(UTF_8), 1024)) {
      assertThatThrownBy(in::readAllBytes)
          .isInstanceOfSatisfying(
              BadRequestException.class, e -> assertThat(e.status()).isEqualTo(400))
          .hasMessageContaining("malformed gzip");
    }
  }

  @Test
  void truncatedBodyFailsWith400() throws IOException {
    byte[] coded = gzip("hello world".getBytes(UTF_8));
    byte[] truncated = Arrays.copyOf(coded, coded.length - 4);
    try (InputStream in = decoding(truncated, 1024)) {
      assertThatThrownBy(in::readAllBytes)
          .isInstanceOfSatisfying(
              BadRequestException.class, e -> assertThat(e.status()).isEqualTo(400));
    }
  }

  @Test
  void closeBeforeReadingClosesTheRawStream() throws IOException {
    TrackingStream raw = new TrackingStream(gzip("x".getBytes(UTF_8)));
    new DecodingInputStream(GZIP, raw, 1024).close();
    assertThat(raw.closed).isTrue();
  }

  @Test
  void closeAfterReadingClosesTheRawStream() throws IOException {
    TrackingStream raw = new TrackingStream(gzip("x".getBytes(UTF_8)));
    try (InputStream in = new DecodingInputStream(GZIP, raw, 1024)) {
      assertThat(in.read()).isEqualTo('x');
    }
    assertThat(raw.closed).isTrue();
  }

  @Test
  void connectionFailureBeforeTheFirstBytePropagatesUnchanged() {
    IOException reset = new IOException("connection reset");
    InputStream in = new DecodingInputStream(GZIP, failing(new byte[0], reset), 1024);

    assertThatThrownBy(in::read).isSameAs(reset);
  }

  @Test
  void connectionFailureMidBodyPropagatesUnchanged() throws IOException {
    IOException reset = new IOException("connection reset");
    byte[] coded = gzip(new byte[4096]);
    InputStream in =
        new DecodingInputStream(
            GZIP, failing(Arrays.copyOf(coded, coded.length / 2), reset), 1 << 20);

    assertThatThrownBy(in::readAllBytes).isSameAs(reset);
  }

  /** A stream that serves {@code prefix}, then fails with {@code failure} instead of ending. */
  private static InputStream failing(byte[] prefix, IOException failure) {
    ByteArrayInputStream served = new ByteArrayInputStream(prefix);
    return new InputStream() {
      @Override
      public int read() throws IOException {
        int b = served.read();
        if (b == -1) {
          throw failure;
        }
        return b;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = served.read(b, off, len);
        if (n == -1) {
          throw failure;
        }
        return n;
      }
    };
  }

  private static InputStream decoding(byte[] coded, long max) {
    return new DecodingInputStream(GZIP, new ByteArrayInputStream(coded), max);
  }

  private static byte[] gzip(byte[] plain) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(plain);
    }
    return out.toByteArray();
  }

  private static final class TrackingStream extends ByteArrayInputStream {
    boolean closed;

    TrackingStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
