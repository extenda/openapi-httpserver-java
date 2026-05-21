package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestResponseGatewayTest extends ServerBaseTest {

  @Test
  void respondJsonWritesBodyAndContentType() throws Exception {
    RequestHandler echo = req -> Response.ok(Map.of("op", req.operationId()));
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", echo, "post-data", echo)))
            .port(0)
            .build();
    HttpClient client =
        HttpClient.newBuilder()
            .executor(newVirtualThreadPerTaskExecutor())
            .version(HTTP_1_1)
            .build();
    var resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"aList\":[\"x\"],\"feelingGood\":true}"))
                .build(),
            ofString());
    assertThat(resp.statusCode()).isEqualTo(HTTP_OK);
    assertThat(resp.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(resp.body()).contains("\"op\":\"post-data\"");
  }

  @Test
  void respondEmptyUses204Style() throws Exception {
    RequestHandler ok = req -> Response.status(HTTP_NO_CONTENT);
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", ok, "post-data", ok)))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            "http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(resp.body()).isEmpty();
  }

  @Test
  void respondStreamUsesChunkedEncoding() throws Exception {
    RequestHandler streamer =
        req ->
            Response.stream(
                HTTP_OK,
                "text/plain",
                out -> {
                  out.write("hello ".getBytes());
                  out.write("world".getBytes());
                });
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", streamer, "post-data", streamer)))
            .port(0)
            .build();
    var resp =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            "http://localhost:%d/api/v1/data".formatted(server.listenPort())))
                    .GET()
                    .build(),
                ofString());
    assertThat(resp.statusCode()).isEqualTo(HTTP_OK);
    assertThat(resp.body()).isEqualTo("hello world");
  }
}
