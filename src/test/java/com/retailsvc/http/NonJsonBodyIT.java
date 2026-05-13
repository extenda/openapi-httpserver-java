package com.retailsvc.http;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.start.FormEchoHandler;
import com.retailsvc.http.start.TextEchoHandler;
import com.sun.net.httpserver.HttpHandler;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NonJsonBodyIT extends ServerBaseTest {

  @Test
  void formUrlEncodedBodyParsedAndCoerced() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers);
        var client = httpClient()) {
      var req = postForm(s, "/form-echo", "name=foo&age=30");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("name=foo").contains("age=30");
    }
  }

  @Test
  void formArrayProperty() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers);
        var client = httpClient()) {
      var req = postForm(s, "/form-echo", "tags=a&tags=b");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("tags=[a, b]");
    }
  }

  @Test
  void formCoercionFailureReturns400() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("form-echo", new FormEchoHandler());
    try (var s = newServer(handlers);
        var client = httpClient()) {
      var req = postForm(s, "/form-echo", "age=abc");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(400);
      assertThat(resp.body()).contains("/age");
    }
  }

  @Test
  void textPlainBodyParsedAsString() throws Exception {
    Map<String, HttpHandler> handlers = Map.of("text-echo", new TextEchoHandler());
    try (var s = newServer(handlers);
        var client = httpClient()) {
      var req = postWithContentType(s, "/text-echo", "hello", "text/plain; charset=utf-8");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).isEqualTo("hello");
    }
  }

  @Test
  void formBodyAgainstJsonOnlyOperationReturns400() throws Exception {
    try (var s = newServer(Map.of());
        var client = httpClient()) {
      var req = postForm(s, "/data", "name=foo");
      var resp = client.send(req, BodyHandlers.ofString());
      assertThat(resp.statusCode()).isEqualTo(400);
      assertThat(resp.body()).contains("content-type");
    }
  }

  private static HttpRequest postForm(OpenApiServer s, String path, String body) {
    return postWithContentType(s, path, body, "application/x-www-form-urlencoded");
  }

  private static HttpRequest postWithContentType(
      OpenApiServer s, String path, String body, String contentType) {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + s.listenPort() + "/api/v1" + path))
        .header("Content-Type", contentType)
        .POST(ofString(body))
        .build();
  }
}
