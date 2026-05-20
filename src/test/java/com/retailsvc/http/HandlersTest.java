package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.internal.BodyWriter;
import com.retailsvc.http.spec.HttpMethod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandlersTest {

  private static final UnaryOperator<String> NO_HEADERS = name -> null;

  private static Request request(HttpMethod method) {
    return new Request(new byte[0], null, null, null, Map.of(), null, NO_HEADERS, Map.of(), method);
  }

  @Test
  void aliveHandlerReturns204OnGet() {
    Response resp = Handlers.aliveHandler().handle(request(GET));

    assertThat(resp.status()).isEqualTo(204);
    assertThat(resp.body()).isNull();
  }

  @Test
  void aliveHandlerReturns204OnHead() {
    Response resp = Handlers.aliveHandler().handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(204);
  }

  @Test
  void aliveHandlerReturns405OnPost() {
    Response resp = Handlers.aliveHandler().handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void resourceHandlerStreamsYamlBytesWithInferredContentType() throws IOException {
    Response resp = Handlers.resourceHandler("/openapi.yaml").handle(request(GET));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.contentType()).isEqualTo("application/yaml");
    assertThat(write(resp)).isEqualTo(readClasspath("/openapi.yaml"));
  }

  @Test
  void resourceHandlerInfersJsonContentType() {
    Response resp = Handlers.resourceHandler("/openapi.json").handle(request(GET));

    assertThat(resp.contentType()).isEqualTo("application/json");
  }

  @Test
  void resourceHandlerThrowsAtConstructionForMissingClasspathResource() {
    assertThatThrownBy(() -> Handlers.resourceHandler("/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.yaml");
  }

  @Test
  void resourceHandlerReturns405OnPost() {
    Response resp = Handlers.resourceHandler("/openapi.yaml").handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void resourceHandlerHeadReturnsContentLengthWithoutBody() {
    Response resp = Handlers.resourceHandler("/openapi.yaml").handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers()).containsKey("Content-Length");
    assertThat(Integer.parseInt(resp.headers().get("Content-Length"))).isGreaterThan(0);
  }

  @Test
  void resourceHandlerOpensClasspathStreamLazilyPerRequest() throws IOException {
    RequestHandler handler = Handlers.resourceHandler("/openapi.yaml");

    byte[] first = write(handler.handle(request(GET)));
    byte[] second = write(handler.handle(request(GET)));

    assertThat(first).isEqualTo(second).isNotEmpty();
  }

  @Test
  void resourceHandlerServesFilesystemFile(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("page.html");
    Files.writeString(file, "<h1>hi</h1>");

    Response resp = Handlers.resourceHandler(file).handle(request(GET));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.contentType()).isEqualTo("text/html; charset=utf-8");
    assertThat(new String(write(resp))).isEqualTo("<h1>hi</h1>");
  }

  @Test
  void resourceHandlerHeadOnFilesystemFileReportsContentLength(@TempDir Path tmp)
      throws IOException {
    Path file = tmp.resolve("data.txt");
    Files.writeString(file, "hello");

    Response resp = Handlers.resourceHandler(file).handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers()).containsEntry("Content-Length", "5");
  }

  @Test
  void resourceHandlerThrowsAtConstructionForMissingFile(@TempDir Path tmp) {
    Path missing = tmp.resolve("nope.txt");

    assertThatThrownBy(() -> Handlers.resourceHandler(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.txt");
  }

  private static byte[] write(Response resp) throws IOException {
    assertThat(resp.body()).isInstanceOf(BodyWriter.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ((BodyWriter) resp.body()).writeTo(out);
    return out.toByteArray();
  }

  private static byte[] readClasspath(String path) throws IOException {
    try (InputStream in = HandlersTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing fixture: " + path);
      }
      return in.readAllBytes();
    }
  }
}
