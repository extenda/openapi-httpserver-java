package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.start.TextEchoHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of gzip request decoding and response coding over a real socket. */
class GzipIT extends ServerBaseTest {

  private static final String TEXT_PLAIN = "text/plain";
  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String ACCEPT_ENCODING = "Accept-Encoding";
  private static final String CONTENT_LENGTH = "Content-Length";

  // -- request decoding --

  @Test
  void gzippedRequestBodyIsDecompressed() throws Exception {
    try (var s = echoServer();
        var client = httpClient()) {
      var request =
          textEcho(s, gzip("hello gzip".getBytes(UTF_8))).header(CONTENT_ENCODING, "gzip").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.body()).isEqualTo("hello gzip");
    }
  }

  @Test
  void identityCodedRequestBodyIsUnaffected() throws Exception {
    try (var s = echoServer();
        var client = httpClient()) {
      var request =
          textEcho(s, "plain".getBytes(UTF_8)).header(CONTENT_ENCODING, "identity").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.body()).isEqualTo("plain");
    }
  }

  @Test
  void handlerDoesNotSeeContentEncodingHeader() throws Exception {
    RequestHandler reportsEncoding =
        req -> Response.text(HTTP_OK, req.header(CONTENT_ENCODING).orElse("absent"));
    try (var s = serverWith(Map.of("text-echo", reportsEncoding));
        var client = httpClient()) {
      var request =
          textEcho(s, gzip("body".getBytes(UTF_8))).header(CONTENT_ENCODING, "gzip").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.body()).isEqualTo("absent");
    }
  }

  @Test
  void unsupportedRequestEncodingReturns415() throws Exception {
    try (var s = echoServer();
        var client = httpClient()) {
      var request = textEcho(s, "body".getBytes(UTF_8)).header(CONTENT_ENCODING, "br").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_UNSUPPORTED_TYPE);
      assertThat(response.headers().firstValue("Content-Type"))
          .contains("application/problem+json");
      assertThat(response.body()).contains("Unsupported Media Type");
    }
  }

  @Test
  void malformedGzipRequestReturns400() throws Exception {
    try (var s = echoServer();
        var client = httpClient()) {
      var request =
          textEcho(s, "not gzip at all".getBytes(UTF_8)).header(CONTENT_ENCODING, "gzip").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(response.body()).contains("malformed gzip request body");
    }
  }

  @Test
  void oversizedGzipRequestReturns413() throws Exception {
    try (var s = echoServer(builder -> builder.maxDecompressedRequestBytes(1024));
        var client = httpClient()) {
      var request = textEcho(s, gzip(new byte[8192])).header(CONTENT_ENCODING, "gzip").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
      assertThat(response.body()).contains("Content Too Large");
    }
  }

  // -- response coding --

  @Test
  void largeResponseIsGzippedWhenClientAcceptsGzip() throws Exception {
    String payload = "compress me please ".repeat(200);
    try (var s = echoServer();
        var client = httpClient()) {
      var request = textEcho(s, payload.getBytes(UTF_8)).header(ACCEPT_ENCODING, "gzip").build();

      HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).contains("gzip");
      assertThat(response.headers().firstValue("Vary"))
          .hasValueSatisfying(vary -> assertThat(vary).contains(ACCEPT_ENCODING));
      assertThat(new String(gunzip(response.body()), UTF_8)).isEqualTo(payload);
      assertThat(response.body().length).isLessThan(payload.length());
    }
  }

  @Test
  void responseIsNotGzippedWithoutAcceptEncoding() throws Exception {
    String payload = "compress me please ".repeat(200);
    try (var s = echoServer();
        var client = httpClient()) {
      var request = textEcho(s, payload.getBytes(UTF_8)).build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).isEmpty();
      assertThat(response.body()).isEqualTo(payload);
    }
  }

  @Test
  void smallResponseIsNotGzipped() throws Exception {
    try (var s = echoServer();
        var client = httpClient()) {
      var request = textEcho(s, "tiny".getBytes(UTF_8)).header(ACCEPT_ENCODING, "gzip").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).isEmpty();
      assertThat(response.body()).isEqualTo("tiny");
    }
  }

  @Test
  void gzipRefusedByQValueIsNotApplied() throws Exception {
    String payload = "compress me please ".repeat(200);
    try (var s = echoServer();
        var client = httpClient()) {
      var request =
          textEcho(s, payload.getBytes(UTF_8)).header(ACCEPT_ENCODING, "gzip;q=0").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).isEmpty();
      assertThat(response.body()).isEqualTo(payload);
    }
  }

  // -- streamed extra routes --

  @Test
  void streamedSpecResourceIsGzippedAndChunked() throws Exception {
    try (var s = specServer();
        var client = httpClient()) {
      var request = specRequest(s).header(ACCEPT_ENCODING, "gzip").GET().build();

      HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.headers().firstValue(CONTENT_ENCODING)).contains("gzip");
      assertThat(response.headers().firstValue(CONTENT_LENGTH)).isEmpty();
      assertThat(gunzip(response.body())).isEqualTo(classpathBytes());
    }
  }

  @Test
  void streamedSpecResourceIsPlainWithoutAcceptEncoding() throws Exception {
    try (var s = specServer();
        var client = httpClient()) {
      var request = specRequest(s).GET().build();

      HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).isEmpty();
      assertThat(response.body()).isEqualTo(classpathBytes());
    }
  }

  @Test
  void headOmitsContentLengthWhenGetWouldBeCompressed() throws Exception {
    try (var s = specServer();
        var client = httpClient()) {
      var request =
          specRequest(s)
              .header(ACCEPT_ENCODING, "gzip")
              .method("HEAD", BodyPublishers.noBody())
              .build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.headers().firstValue(CONTENT_LENGTH)).isEmpty();
      assertThat(response.headers().firstValue("Content-Type")).contains("application/yaml");
    }
  }

  @Test
  void headKeepsContentLengthWithoutAcceptEncoding() throws Exception {
    try (var s = specServer();
        var client = httpClient()) {
      var request = specRequest(s).method("HEAD", BodyPublishers.noBody()).build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.headers().firstValue(CONTENT_LENGTH))
          .contains(String.valueOf(classpathBytes().length));
    }
  }

  // -- fixtures --

  private OpenApiServer echoServer() {
    return echoServer(builder -> builder);
  }

  private OpenApiServer echoServer(UnaryOperator<OpenApiServer.Builder> customise) {
    return serverWith(Map.of("text-echo", new TextEchoHandler()), customise);
  }

  private OpenApiServer serverWith(Map<String, RequestHandler> handlers) {
    return serverWith(handlers, builder -> builder);
  }

  private OpenApiServer serverWith(
      Map<String, RequestHandler> handlers, UnaryOperator<OpenApiServer.Builder> customise) {
    try {
      server =
          customise
              .apply(newBuilder().spec(spec).handlers(stubAllHandlers(handlers)).port(0))
              .build();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private OpenApiServer specServer() {
    return serverWith(
        Map.of(),
        builder -> builder.extraRoute("/openapi.yaml", Handlers.resourceHandler("/openapi.yaml")));
  }

  private HttpRequest.Builder textEcho(OpenApiServer server, byte[] body) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:%d/api/v1/text-echo".formatted(server.listenPort())))
        .header("Content-Type", TEXT_PLAIN)
        .POST(BodyPublishers.ofByteArray(body));
  }

  private HttpRequest.Builder specRequest(OpenApiServer server) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:%d/openapi.yaml".formatted(server.listenPort())));
  }

  private static byte[] classpathBytes() throws IOException {
    try (InputStream in = GzipIT.class.getResourceAsStream("/openapi.yaml")) {
      return in.readAllBytes();
    }
  }

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(data);
    }
    return out.toByteArray();
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }
}
