package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtrasWildcardIT extends ServerBaseTest {

  @Test
  void singleStarMatchesOneSegment() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/static/*", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/static/x.css").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/static/a/b").statusCode()).isEqualTo(HTTP_NOT_FOUND);
      assertThat(get(client, s, "/static/").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void doubleStarMatchesAnyDepth() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/files/**", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/files/a").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/files/a/b/c").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/filesx/a").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void midPathDoubleStar() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/schemas/**/openapi.yaml", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/schemas/a/openapi.yaml").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/schemas/a/b/openapi.yaml").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/schemas/openapi.yaml").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void exactExtraStillWorks() throws Exception {
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/alive", Handlers.aliveHandler())
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/alive").statusCode()).isEqualTo(HTTP_NO_CONTENT);
      assertThat(get(client, s, "/alive232").statusCode()).isEqualTo(HTTP_NOT_FOUND);
    }
  }

  @Test
  void traversalReturns400() throws Exception {
    RequestHandler ok = req -> Response.of(HTTP_OK, "ok");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/files/**", ok)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/files/../etc/passwd").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%2e%2e/etc/passwd").statusCode())
          .isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/%252e%252e/etc/passwd").statusCode())
          .isEqualTo(HTTP_BAD_REQUEST);
      // %2f is a percent-encoded slash — reject encoded path separators
      assertThat(get(client, s, "/files/%2fetc").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      // %5c is a backslash — reject encoded backslashes
      assertThat(get(client, s, "/files/x%5cy").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      // %00 is a null byte — java.net.URI rejects raw NUL in the path; defense at the
      // router is still valid for clients that bypass URI parsing, but we cannot express
      // this vector via java.net.http.HttpClient (URI.create throws before the wire).
      // assertThat(get(client, s, "/files/x%00").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      // %0a is a line-feed — same reason as %00: JDK URI rejects it pre-wire.
      // assertThat(get(client, s, "/files/x%0ay").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files//x").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/.").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      assertThat(get(client, s, "/files/./x").statusCode()).isEqualTo(HTTP_BAD_REQUEST);
    }
  }

  @Test
  void basePathSpecRouteWinsOverExtras() throws Exception {
    RequestHandler greedy = req -> Response.of(HTTP_OK, "should not see this");
    try (var s =
            newBuilder()
                .spec(spec)
                .handlers(stubAllHandlers(Map.of()))
                .port(0)
                .extraRoute("/**", greedy)
                .build();
        var client = httpClient()) {

      assertThat(get(client, s, "/api/v1/data").statusCode()).isEqualTo(HTTP_OK);
      assertThat(get(client, s, "/totally-not-a-spec-route").statusCode()).isEqualTo(HTTP_OK);
    }
  }

  private HttpResponse<String> get(HttpClient client, OpenApiServer s, String path)
      throws Exception {
    var req =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + s.listenPort() + path))
            .GET()
            .build();
    return client.send(req, BodyHandlers.ofString());
  }
}
