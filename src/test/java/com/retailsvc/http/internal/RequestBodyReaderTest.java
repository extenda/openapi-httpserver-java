package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.BadRequestException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

class RequestBodyReaderTest {

  private static final long CAP = 1024;
  private final RequestBodyReader reader = new RequestBodyReader(CAP);

  @Test
  void constructorRejectsNonPositiveCap() {
    assertThatThrownBy(() -> new RequestBodyReader(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxDecompressedBytes");
    assertThatThrownBy(() -> new RequestBodyReader(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void plainBodyIsReturnedUnchanged() throws IOException {
    byte[] raw = "hello".getBytes(UTF_8);

    RequestBodyReader.Body body = reader.read(exchange(raw, null));

    assertThat(body.bytes()).isEqualTo(raw);
  }

  @Test
  void identityCodedBodyIsReturnedUnchanged() throws IOException {
    byte[] raw = "hello".getBytes(UTF_8);

    RequestBodyReader.Body body = reader.read(exchange(raw, "identity"));

    assertThat(body.bytes()).isEqualTo(raw);
  }

  @Test
  void gzipBodyIsInflated() throws IOException {
    byte[] plain = "hello gzip".getBytes(UTF_8);

    RequestBodyReader.Body body = reader.read(exchange(gzip(plain), "gzip"));

    assertThat(body.bytes()).isEqualTo(plain);
  }

  @Test
  void emptyGzipBodyIsReturnedEmpty() throws IOException {
    RequestBodyReader.Body body = reader.read(exchange(new byte[0], "gzip"));

    assertThat(body.bytes()).isEmpty();
  }

  @Test
  void unsupportedCodingThrows415() {
    assertThatThrownBy(() -> reader.read(exchange("x".getBytes(UTF_8), "br")))
        .isInstanceOfSatisfying(
            BadRequestException.class,
            e -> assertThat(e.status()).isEqualTo(HTTP_UNSUPPORTED_TYPE));
  }

  @Test
  void oversizedInflatedBodyThrows413() throws IOException {
    byte[] bomb = new byte[(int) CAP * 4];

    assertThatThrownBy(() -> reader.read(exchange(gzip(bomb), "gzip")))
        .isInstanceOfSatisfying(
            BadRequestException.class,
            e -> assertThat(e.status()).isEqualTo(HTTP_ENTITY_TOO_LARGE));
  }

  @Test
  void bodyExactlyAtCapIsAccepted() throws IOException {
    byte[] atLimit = new byte[(int) CAP];

    RequestBodyReader.Body body = reader.read(exchange(gzip(atLimit), "gzip"));

    assertThat(body.bytes()).hasSize((int) CAP);
  }

  @Test
  void malformedGzipThrows400WithCause() {
    byte[] garbage = "not gzip at all".getBytes(UTF_8);

    assertThatThrownBy(() -> reader.read(exchange(garbage, "gzip")))
        .isInstanceOfSatisfying(
            BadRequestException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HTTP_BAD_REQUEST);
              assertThat(e.getCause()).isInstanceOf(ZipException.class);
            });
  }

  @Test
  void truncatedGzipThrows400() throws IOException {
    byte[] complete = gzip("some reasonably long payload to truncate".getBytes(UTF_8));
    byte[] truncated = Arrays.copyOf(complete, complete.length - 6);

    assertThatThrownBy(() -> reader.read(exchange(truncated, "gzip")))
        .isInstanceOfSatisfying(
            BadRequestException.class, e -> assertThat(e.status()).isEqualTo(HTTP_BAD_REQUEST));
  }

  @Test
  void decodedBodyHidesContentEncodingHeader() throws IOException {
    RequestBodyReader.Body body = reader.read(exchange(gzip("hi".getBytes(UTF_8)), "gzip"));

    assertThat(body.headerLookup().apply("Content-Encoding")).isNull();
    assertThat(body.headerLookup().apply("content-encoding")).isNull();
  }

  @Test
  void decodedBodyReportsInflatedContentLength() throws IOException {
    byte[] plain = "hello gzip".getBytes(UTF_8);

    RequestBodyReader.Body body = reader.read(exchange(gzip(plain), "gzip"));

    assertThat(body.headerLookup().apply("Content-Length")).isEqualTo(String.valueOf(plain.length));
  }

  @Test
  void decodedBodyLeavesOtherHeadersVisible() throws IOException {
    RequestBodyReader.Body body = reader.read(exchange(gzip("hi".getBytes(UTF_8)), "gzip"));

    assertThat(body.headerLookup().apply("X-Custom")).isEqualTo("value");
  }

  @Test
  void plainBodyKeepsOriginalHeaderLookup() throws IOException {
    RequestBodyReader.Body body = reader.read(exchange("hi".getBytes(UTF_8), "identity"));

    assertThat(body.headerLookup().apply("Content-Encoding")).isEqualTo("identity");
    assertThat(body.headerLookup().apply("X-Custom")).isEqualTo("value");
  }

  private static HttpExchange exchange(byte[] body, String contentEncoding) {
    Headers headers = new Headers();
    if (contentEncoding != null) {
      headers.add("Content-Encoding", contentEncoding);
    }
    headers.add("Content-Length", String.valueOf(body.length));
    headers.add("X-Custom", "value");
    HttpExchange exchange = mock(HttpExchange.class);
    when(exchange.getRequestHeaders()).thenReturn(headers);
    when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
    return exchange;
  }

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(data);
    }
    return out.toByteArray();
  }
}
