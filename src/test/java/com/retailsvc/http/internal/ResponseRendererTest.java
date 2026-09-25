package com.retailsvc.http.internal;

import static com.retailsvc.http.support.TestCodings.deflate;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.ContentCoding;
import com.retailsvc.http.GsonTypeMapper;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResponseRendererTest {

  private static final Map<String, TypeMapper> MAPPERS =
      Map.of("application/json", new GsonTypeMapper());
  private static final long THRESHOLD = 1024;
  private static final List<ContentCoding> GZIP_ONLY =
      ContentCodings.of(List.of(), List.of()).encoders();
  private static final String JSON = "application/json";
  private static final String TEXT = "text/plain";
  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String VARY = "Vary";

  private final ResponseRenderer renderer = new ResponseRenderer(MAPPERS, THRESHOLD, GZIP_ONLY);
  private final Headers requestHeaders = new Headers();
  private final Headers responseHeaders = new Headers();
  private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
  private final AtomicInteger status = new AtomicInteger();
  private final AtomicLong length = new AtomicLong();
  private HttpExchange exchange;

  @BeforeEach
  void setUp() throws IOException {
    exchange = mock(HttpExchange.class);
    when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
    when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
    when(exchange.getResponseBody()).thenReturn(sink);
    doAnswer(
            invocation -> {
              status.set(invocation.getArgument(0));
              length.set(invocation.getArgument(1));
              return null;
            })
        .when(exchange)
        .sendResponseHeaders(anyInt(), anyLong());
  }

  // -- baseline behaviour --

  @Test
  void writesBytesWithContentLength() throws IOException {
    renderer.render(exchange, Response.bytes(HTTP_OK, "abc".getBytes(UTF_8), TEXT));

    assertThat(status.get()).isEqualTo(HTTP_OK);
    assertThat(length.get()).isEqualTo(3);
    assertThat(sink.toByteArray()).isEqualTo("abc".getBytes(UTF_8));
    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo(TEXT);
  }

  @Test
  void writesNullBodyWithMinusOne() throws IOException {
    renderer.render(exchange, Response.status(HTTP_NO_CONTENT));

    assertThat(length.get()).isEqualTo(-1);
    assertThat(sink.toByteArray()).isEmpty();
  }

  @Test
  void writesEmptyByteBodyWithMinusOne() throws IOException {
    renderer.render(exchange, Response.bytes(HTTP_OK, new byte[0], TEXT));

    assertThat(length.get()).isEqualTo(-1);
  }

  // -- compression --

  @Test
  void compressesJsonBodyOverThreshold() throws IOException {
    acceptsGzip();
    byte[] body = largeText();

    renderer.render(exchange, Response.bytes(HTTP_OK, body, JSON));

    assertThat(gunzip(sink.toByteArray())).isEqualTo(body);
  }

  @Test
  void setsContentEncodingGzipWhenCompressed() throws IOException {
    acceptsGzip();

    renderer.render(exchange, Response.bytes(HTTP_OK, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(responseHeaders.getFirst(VARY)).contains("Accept-Encoding");
  }

  @Test
  void sentContentLengthMatchesCompressedPayload() throws IOException {
    acceptsGzip();

    renderer.render(exchange, Response.bytes(HTTP_OK, largeText(), JSON));

    assertThat(length.get()).isEqualTo(sink.toByteArray().length);
    assertThat(length.get()).isLessThan(largeText().length);
  }

  @Test
  void skipsCompressionBelowThreshold() throws IOException {
    acceptsGzip();
    byte[] body = "{\"id\":\"small\"}".getBytes(UTF_8);

    renderer.render(exchange, Response.bytes(HTTP_OK, body, JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(body);
  }

  @Test
  void skipsCompressionForOctetStream() throws IOException {
    acceptsGzip();
    byte[] body = largeText();

    renderer.render(exchange, Response.bytes(HTTP_OK, body, "application/octet-stream"));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(body);
  }

  @Test
  void skipsCompressionWithoutAcceptEncoding() throws IOException {
    byte[] body = largeText();

    renderer.render(exchange, Response.bytes(HTTP_OK, body, JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(body);
  }

  @Test
  void skipsCompressionWhenGzipRefusedByQValue() throws IOException {
    requestHeaders.add("Accept-Encoding", "gzip;q=0");

    renderer.render(exchange, Response.bytes(HTTP_OK, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
  }

  @Test
  void skipsCompressionWhenHandlerAlreadySetContentEncoding() throws IOException {
    acceptsGzip();
    byte[] body = largeText();

    renderer.render(
        exchange, Response.bytes(HTTP_OK, body, JSON).withHeader(CONTENT_ENCODING, "br"));

    assertThat(responseHeaders.get(CONTENT_ENCODING)).containsExactly("br");
    assertThat(sink.toByteArray()).isEqualTo(body);
  }

  @Test
  void addsVaryEvenWhenNotCompressed() throws IOException {
    renderer.render(exchange, Response.bytes(HTTP_OK, "{}".getBytes(UTF_8), JSON));

    assertThat(responseHeaders.getFirst(VARY)).isEqualTo("Accept-Encoding");
  }

  @Test
  void appendsVaryToExistingValue() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange, Response.bytes(HTTP_OK, largeText(), JSON).withHeader(VARY, "Origin"));

    assertThat(responseHeaders.get(VARY)).hasSize(1);
    assertThat(responseHeaders.getFirst(VARY)).isEqualTo("Origin, Accept-Encoding");
  }

  @Test
  void doesNotDuplicateVary() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange, Response.bytes(HTTP_OK, largeText(), JSON).withHeader(VARY, "Accept-Encoding"));

    assertThat(responseHeaders.get(VARY)).containsExactly("Accept-Encoding");
  }

  @Test
  void neverCompressesNoContentResponses() throws IOException {
    acceptsGzip();

    renderer.render(exchange, Response.bytes(HTTP_NO_CONTENT, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
  }

  @Test
  void fallsBackToPlainBytesWhenGzipIsLarger() throws IOException {
    acceptsGzip();
    byte[] incompressible = new byte[2048];
    new Random(42).nextBytes(incompressible);

    renderer.render(exchange, Response.bytes(HTTP_OK, incompressible, TEXT));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(incompressible);
  }

  @Test
  void serializedBodyIsCompressed() throws IOException {
    acceptsGzip();

    renderer.render(exchange, Response.ok(Map.of("blob", "x".repeat(4096))));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(new String(gunzip(sink.toByteArray()), UTF_8)).contains("xxxx");
  }

  // -- streaming and empty bodies --

  @Test
  void writesSizedStreamWithLength() throws IOException {
    renderer.render(
        exchange, Response.stream(HTTP_OK, 3, TEXT, out -> out.write("abc".getBytes(UTF_8))));

    assertThat(length.get()).isEqualTo(3);
    assertThat(sink.toByteArray()).isEqualTo("abc".getBytes(UTF_8));
  }

  @Test
  void compressesChunkedStreamWhenAcceptEncodingPresent() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(exchange, Response.stream(HTTP_OK, TEXT, out -> out.write(payload)));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(length.get()).isZero();
    assertThat(gunzip(sink.toByteArray())).isEqualTo(payload);
  }

  @Test
  void degradesSizedStreamToChunkedWhenCompressed() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(
        exchange, Response.stream(HTTP_OK, payload.length, TEXT, out -> out.write(payload)));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(length.get()).isZero();
    assertThat(gunzip(sink.toByteArray())).isEqualTo(payload);
  }

  @Test
  void skipsCompressionForSizedStreamBelowThreshold() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange, Response.stream(HTTP_OK, 3, TEXT, out -> out.write("abc".getBytes(UTF_8))));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(length.get()).isEqualTo(3);
    assertThat(sink.toByteArray()).isEqualTo("abc".getBytes(UTF_8));
  }

  @Test
  void skipsCompressionForNullContentTypeStream() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(exchange, Response.stream(HTTP_OK, null, out -> out.write(payload)));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(payload);
  }

  @Test
  void emitsContentTypeOnNullBodyResponses() throws IOException {
    renderer.render(exchange, Response.status(HTTP_OK).withContentType("application/yaml"));

    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/yaml");
    assertThat(length.get()).isEqualTo(-1);
  }

  @Test
  void stripsContentLengthOnNullBodyWhenGetWouldCompress() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange,
        Response.status(HTTP_OK)
            .withContentType("application/yaml")
            .withHeader("Content-Length", "8506"));

    assertThat(responseHeaders.getFirst("Content-Length")).isNull();
    assertThat(responseHeaders.getFirst(VARY)).isEqualTo("Accept-Encoding");
    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
  }

  @Test
  void keepsContentLengthOnNullBodyWhenClientDoesNotAcceptGzip() throws IOException {
    renderer.render(
        exchange,
        Response.status(HTTP_OK)
            .withContentType("application/yaml")
            .withHeader("Content-Length", "8506"));

    assertThat(responseHeaders.getFirst("Content-Length")).isEqualTo("8506");
  }

  @Test
  void keepsContentLengthOnNullBodyForNonCompressibleType() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange,
        Response.status(HTTP_OK).withContentType("image/png").withHeader("Content-Length", "8506"));

    assertThat(responseHeaders.getFirst("Content-Length")).isEqualTo("8506");
  }

  @Test
  void keepsContentLengthOnNullBodyBelowThreshold() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange,
        Response.status(HTTP_OK).withContentType(TEXT).withHeader("Content-Length", "12"));

    assertThat(responseHeaders.getFirst("Content-Length")).isEqualTo("12");
  }

  @Test
  void neverCompressesNotModifiedResponses() throws IOException {
    acceptsGzip();

    renderer.render(exchange, Response.bytes(HTTP_NOT_MODIFIED, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
  }

  @Test
  void keepsContentLengthOnNullBodyWhenUnparsable() throws IOException {
    acceptsGzip();

    renderer.render(
        exchange,
        Response.status(HTTP_OK)
            .withContentType(TEXT)
            .withHeader("Content-Length", "not-a-number"));

    assertThat(responseHeaders.getFirst("Content-Length")).isEqualTo("not-a-number");
  }

  @Test
  void keepsHandlerSuppliedContentTypeOnStreams() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(
        exchange,
        Response.stream(HTTP_OK, TEXT, out -> out.write(payload))
            .withHeader("Content-Type", "text/csv"));

    assertThat(responseHeaders.get("Content-Type")).containsExactly("text/csv");
    assertThat(gunzip(sink.toByteArray())).isEqualTo(payload);
  }

  @Test
  void skipsCompressionOnStreamWhenHandlerAlreadySetContentEncoding() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(
        exchange,
        Response.stream(HTTP_OK, TEXT, out -> out.write(payload))
            .withHeader(CONTENT_ENCODING, "br"));

    assertThat(responseHeaders.get(CONTENT_ENCODING)).containsExactly("br");
    assertThat(sink.toByteArray()).isEqualTo(payload);
  }

  @Test
  void stripsHandlerContentLengthWhenStreamIsCompressed() throws IOException {
    acceptsGzip();
    byte[] payload = largeText();

    renderer.render(
        exchange,
        Response.stream(HTTP_OK, payload.length, TEXT, out -> out.write(payload))
            .withHeader("Content-Length", String.valueOf(payload.length)));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
    assertThat(responseHeaders.getFirst("Content-Length")).isNull();
    assertThat(length.get()).isZero();
  }

  // -- registered codings --

  @Test
  void registeredCodingCodesTheBodyAndNamesItself() throws IOException {
    requestHeaders.add("Accept-Encoding", "deflate");
    byte[] body = largeText();

    withDeflate().render(exchange, Response.bytes(HTTP_OK, body, JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("deflate");
    assertThat(inflate(sink.toByteArray())).isEqualTo(body);
  }

  @Test
  void registeredCodingCodesAStream() throws IOException {
    requestHeaders.add("Accept-Encoding", "deflate");
    byte[] payload = largeText();

    withDeflate().render(exchange, Response.stream(HTTP_OK, TEXT, out -> out.write(payload)));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("deflate");
    assertThat(length.get()).isZero();
    assertThat(inflate(sink.toByteArray())).isEqualTo(payload);
  }

  @Test
  void registeredCodingWinsOverGzipWhenWeightedEqually() throws IOException {
    requestHeaders.add("Accept-Encoding", "gzip, deflate");

    withDeflate().render(exchange, Response.bytes(HTTP_OK, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("deflate");
  }

  @Test
  void gzipStillServesClientsThatOnlyTakeGzip() throws IOException {
    requestHeaders.add("Accept-Encoding", "gzip");

    withDeflate().render(exchange, Response.bytes(HTTP_OK, largeText(), JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isEqualTo("gzip");
  }

  @Test
  void codingThatDoesNotShrinkTheBodyFallsBackToIdentity() throws IOException {
    requestHeaders.add("Accept-Encoding", "bloat");
    byte[] body = largeText();
    ResponseRenderer bloating =
        new ResponseRenderer(
            MAPPERS, THRESHOLD, ContentCodings.of(List.of(), List.of(bloat())).encoders());

    bloating.render(exchange, Response.bytes(HTTP_OK, body, JSON));

    assertThat(responseHeaders.getFirst(CONTENT_ENCODING)).isNull();
    assertThat(sink.toByteArray()).isEqualTo(body);
  }

  private static ResponseRenderer withDeflate() {
    return new ResponseRenderer(
        MAPPERS, THRESHOLD, ContentCodings.of(List.of(), List.of(deflate())).encoders());
  }

  /** A coding that doubles every byte, so it can never shrink a body. */
  private static ContentCoding bloat() {
    return new ContentCoding() {
      @Override
      public String token() {
        return "bloat";
      }

      @Override
      public InputStream decode(InputStream coded) {
        return coded;
      }

      @Override
      public OutputStream encode(OutputStream sink) {
        return new FilterOutputStream(sink) {
          @Override
          public void write(int b) throws IOException {
            out.write(b);
            out.write(b);
          }
        };
      }
    };
  }

  private static byte[] inflate(byte[] data) throws IOException {
    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }

  private void acceptsGzip() {
    requestHeaders.add("Accept-Encoding", "gzip, deflate, br");
  }

  private static byte[] largeText() {
    return ("{\"blob\":\"" + "abcdefgh".repeat(300) + "\"}").getBytes(UTF_8);
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }
}
