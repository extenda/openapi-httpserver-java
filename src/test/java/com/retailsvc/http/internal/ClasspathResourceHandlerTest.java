package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ClasspathResourceHandlerTest {

  @Test
  void getServesBytesVerbatim() throws IOException {
    byte[] expected = readResource("/sample.txt");
    HttpExchange ex = newExchange("GET");
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    new ClasspathResourceHandler("/sample.txt").handle(ex);

    verify(ex).sendResponseHeaders(200, expected.length);
    assertThat(body.toByteArray()).isEqualTo(expected);
  }

  @Test
  void headSendsContentLengthHeaderWithoutBody() throws IOException {
    byte[] expected = readResource("/sample.txt");
    HttpExchange ex = newExchange("HEAD");
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);

    new ClasspathResourceHandler("/sample.txt").handle(ex);

    verify(ex).sendResponseHeaders(200, -1);
    assertThat(responseHeaders.getFirst("Content-Length"))
        .isEqualTo(String.valueOf(expected.length));
  }

  @Test
  void infersApplicationJsonForJsonExtension() throws IOException {
    assertThat(contentTypeFor("/openapi.json")).isEqualTo("application/json");
  }

  @Test
  void infersApplicationYamlForYamlExtension() throws IOException {
    assertThat(contentTypeFor("/openapi.yaml")).isEqualTo("application/yaml");
  }

  @Test
  void infersTextPlainForTxtExtension() throws IOException {
    assertThat(contentTypeFor("/sample.txt")).isEqualTo("text/plain; charset=utf-8");
  }

  @Test
  void fallsBackToOctetStreamForUnknownExtension() throws IOException {
    assertThat(contentTypeFor("/sample.bin")).isEqualTo("application/octet-stream");
  }

  @Test
  void missingResourceThrowsIllegalArgumentExceptionWithPathInMessage() {
    assertThatThrownBy(() -> new ClasspathResourceHandler("/does-not-exist.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.json");
  }

  @Test
  void resourceIsLoadedEagerlyAtConstruction() {
    // If the resource were loaded lazily, construction would succeed and the handle()
    // call would fail. Construction itself must fail.
    assertThatThrownBy(() -> new ClasspathResourceHandler("/missing.txt"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void contentLengthIsSetForGetRequests() throws IOException {
    HttpExchange ex = newExchange("GET");
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    new ClasspathResourceHandler("/sample.txt").handle(ex);

    verify(ex).sendResponseHeaders(eq(200), longThat(n -> n > 0));
  }

  private static String contentTypeFor(String resource) throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    new ClasspathResourceHandler(resource).handle(ex);
    return headers.getFirst("Content-Type");
  }

  private static HttpExchange newExchange(String method) {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn(method);
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    return ex;
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
