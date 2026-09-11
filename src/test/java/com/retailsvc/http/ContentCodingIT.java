package com.retailsvc.http;

import static com.retailsvc.http.support.TestCodings.deflate;
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
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of a caller-registered content coding. The coding is {@code deflate} from
 * java.util.zip, so the extension point is proved without any compression dependency.
 */
class ContentCodingIT extends ServerBaseTest {

  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String ACCEPT_ENCODING = "Accept-Encoding";
  private static final String PAYLOAD = "compress me please ".repeat(200);

  // -- requests --

  @Test
  void deflatedRequestBodyIsDecoded() throws Exception {
    try (var s = echoServer(b -> b.contentCoding(deflate()));
        var client = httpClient()) {
      var request =
          textEcho(s, deflated("hello deflate".getBytes(UTF_8)))
              .header(CONTENT_ENCODING, "deflate")
              .build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.body()).isEqualTo("hello deflate");
    }
  }

  @Test
  void deflateBombIsStoppedByTheServersCap() throws Exception {
    try (var s = echoServer(b -> b.contentCoding(deflate()).maxDecompressedRequestBytes(1024));
        var client = httpClient()) {
      var request =
          textEcho(s, deflated(new byte[8192])).header(CONTENT_ENCODING, "deflate").build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
    }
  }

  @Test
  void malformedDeflateBodyReturns400() throws Exception {
    try (var s = echoServer(b -> b.contentCoding(deflate()));
        var client = httpClient()) {
      var request =
          textEcho(s, "not deflate at all".getBytes(UTF_8))
              .header(CONTENT_ENCODING, "deflate")
              .build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(response.body()).contains("malformed deflate request body");
    }
  }

  // -- responses --

  @Test
  void responseIsCodedWithTheRegisteredCoding() throws Exception {
    try (var s = echoServer(b -> b.contentCoding(deflate()));
        var client = httpClient()) {
      var request = textEcho(s, PAYLOAD.getBytes(UTF_8)).header(ACCEPT_ENCODING, "deflate").build();

      HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).contains("deflate");
      assertThat(response.headers().firstValue("Vary"))
          .hasValueSatisfying(vary -> assertThat(vary).contains(ACCEPT_ENCODING));
      assertThat(new String(inflate(response.body()), UTF_8)).isEqualTo(PAYLOAD);
    }
  }

  @Test
  void registeredCodingWinsATieWithGzip() throws Exception {
    assertThat(codingChosenFor("gzip, deflate")).contains("deflate");
  }

  @Test
  void clientWeightStillDecides() throws Exception {
    assertThat(codingChosenFor("deflate;q=0.1, gzip;q=0.9")).contains("gzip");
  }

  @Test
  void streamedResponseUsesTheRegisteredCoding() throws Exception {
    try (var s =
            echoServer(
                b ->
                    b.contentCoding(deflate())
                        .extraRoute("/openapi.yaml", Handlers.resourceHandler("/openapi.yaml")));
        var client = httpClient()) {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://localhost:%d/openapi.yaml".formatted(s.listenPort())))
              .header(ACCEPT_ENCODING, "deflate")
              .GET()
              .build();

      HttpResponse<byte[]> response = client.send(request, BodyHandlers.ofByteArray());

      assertThat(response.headers().firstValue(CONTENT_ENCODING)).contains("deflate");
      assertThat(response.headers().firstValue("Content-Length")).isEmpty();
      assertThat(inflate(response.body())).isEqualTo(classpathBytes());
    }
  }

  // -- one direction only --

  @Test
  void requestOnlyCodingDecodesButNeverCodesAResponse() throws Exception {
    try (var s = echoServer(b -> b.requestContentCoding(deflate()));
        var client = httpClient()) {
      var request =
          textEcho(s, deflated(PAYLOAD.getBytes(UTF_8)))
              .header(CONTENT_ENCODING, "deflate")
              .header(ACCEPT_ENCODING, "deflate")
              .build();

      var response = client.send(request, BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
      assertThat(response.headers().firstValue(CONTENT_ENCODING)).isEmpty();
      assertThat(response.body()).isEqualTo(PAYLOAD);
    }
  }

  @Test
  void responseOnlyCodingCodesButIsRefusedOnRequests() throws Exception {
    try (var s = echoServer(b -> b.responseContentCoding(deflate()));
        var client = httpClient()) {
      var coded = textEcho(s, PAYLOAD.getBytes(UTF_8)).header(ACCEPT_ENCODING, "deflate").build();
      var refused =
          textEcho(s, deflated(PAYLOAD.getBytes(UTF_8)))
              .header(CONTENT_ENCODING, "deflate")
              .build();

      var codedResponse = client.send(coded, BodyHandlers.ofByteArray());
      var refusedResponse = client.send(refused, BodyHandlers.ofString());

      assertThat(codedResponse.headers().firstValue(CONTENT_ENCODING)).contains("deflate");
      assertThat(refusedResponse.statusCode()).isEqualTo(HTTP_UNSUPPORTED_TYPE);
    }
  }

  // -- fixtures --

  private Optional<String> codingChosenFor(String acceptEncoding) throws Exception {
    try (var s = echoServer(b -> b.contentCoding(deflate()));
        var client = httpClient()) {
      var request =
          textEcho(s, PAYLOAD.getBytes(UTF_8)).header(ACCEPT_ENCODING, acceptEncoding).build();
      return client
          .send(request, BodyHandlers.ofByteArray())
          .headers()
          .firstValue(CONTENT_ENCODING);
    }
  }

  private OpenApiServer echoServer(UnaryOperator<OpenApiServer.Builder> customise) {
    try {
      server =
          customise
              .apply(
                  newBuilder()
                      .spec(spec)
                      .handlers(stubAllHandlers(Map.of("text-echo", new TextEchoHandler())))
                      .port(0))
              .build();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpRequest.Builder textEcho(OpenApiServer server, byte[] body) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:%d/api/v1/text-echo".formatted(server.listenPort())))
        .header("Content-Type", "text/plain")
        .POST(BodyPublishers.ofByteArray(body));
  }

  private static byte[] classpathBytes() throws IOException {
    try (InputStream in = ContentCodingIT.class.getResourceAsStream("/openapi.yaml")) {
      return in.readAllBytes();
    }
  }

  private static byte[] deflated(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
      deflater.write(data);
    }
    return out.toByteArray();
  }

  private static byte[] inflate(byte[] data) throws IOException {
    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }
}
