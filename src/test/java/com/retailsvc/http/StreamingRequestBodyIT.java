package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.Credential.ApiKeyCredential;
import com.retailsvc.http.spec.Spec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end coverage of {@link StreamingRequestHandler}: bodies read as they arrive. */
class StreamingRequestBodyIT {

  private static final String OCTET_STREAM = "application/octet-stream";
  private static final String UPLOAD_ID = "Upload-Id";
  private static final String RECEIVED = "Received-Bytes";

  private final Spec spec = loadSpec();
  private OpenApiServer server;

  @AfterEach
  void tearDown() {
    Optional.ofNullable(server).ifPresent(OpenApiServer::close);
  }

  @Test
  void identityBodyLargerThanTheDecompressionCapIsStreamed() throws Exception {
    byte[] payload = new byte[8 * 1024 * 1024];
    start(Map.of("upload", counting()), b -> b.maxDecompressedRequestBytes(1024));

    var response = send(upload(payload));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue(String.valueOf(payload.length));
  }

  @Test
  void handlerSeesAStreamingRequestWithoutBufferedViews() throws Exception {
    AtomicReference<Request> seen = new AtomicReference<>();
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  seen.set(request);
                  drain(request);
                  return Response.ok();
                }),
        b -> b);

    var response = send(upload("abc".getBytes(UTF_8)));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    Request request = seen.get();
    assertThat(request.isStreaming()).isTrue();
    assertThat(request.operationId()).isEqualTo("upload");
    assertThat(request.header(UPLOAD_ID)).hasValue("u-1");
    assertThatThrownBy(request::bytes).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(request::parsed).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> request.asPojo(Map.class)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void gzipBodyIsDecodedAsItIsRead() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<Optional<String>> encoding = new AtomicReference<>();
    AtomicReference<Optional<String>> length = new AtomicReference<>();
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  body.set(new String(drain(request), UTF_8));
                  encoding.set(request.header("Content-Encoding"));
                  length.set(request.header("Content-Length"));
                  return Response.ok();
                }),
        b -> b);

    var response =
        send(upload(gzip("hello stream".getBytes(UTF_8))).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(body.get()).isEqualTo("hello stream");
    assertThat(encoding.get()).isEmpty();
    assertThat(length.get()).isEmpty();
  }

  @Test
  void gzipBodyOverTheCapFailsTheReadWith413() throws Exception {
    start(Map.of("upload", counting()), b -> b.maxDecompressedRequestBytes(1024));

    var response = send(upload(gzip(new byte[64 * 1024])).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
  }

  @Test
  void streamingCapLetsADecodedBodyPassTheBufferedCap() throws Exception {
    start(
        Map.of("upload", counting()),
        b ->
            b.maxDecompressedRequestBytes(1024)
                .maxDecompressedStreamingRequestBytes(Long.MAX_VALUE));

    var response = send(upload(gzip(new byte[64 * 1024])).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue(String.valueOf(64 * 1024));
  }

  @Test
  void gzipBodyOverTheStreamingCapFailsTheReadWith413() throws Exception {
    start(
        Map.of("upload", counting()),
        b -> b.maxDecompressedRequestBytes(1024 * 1024).maxDecompressedStreamingRequestBytes(1024));

    var response = send(upload(gzip(new byte[64 * 1024])).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
  }

  @Test
  void malformedGzipBodyIs400() throws Exception {
    start(Map.of("upload", counting()), b -> b);

    var response =
        send(upload("not gzip at all".getBytes(UTF_8)).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
  }

  @Test
  void unsupportedContentEncodingIs415() throws Exception {
    start(Map.of("upload", counting()), b -> b);

    var response = send(upload("abc".getBytes(UTF_8)).header("Content-Encoding", "br"));

    assertThat(response.statusCode()).isEqualTo(HTTP_UNSUPPORTED_TYPE);
  }

  @Test
  void parametersAreStillValidated() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    start(Map.of("upload", invokedFlag(invoked)), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/uploads"))
                .header("Content-Type", OCTET_STREAM)
                .POST(BodyPublishers.ofByteArray("abc".getBytes(UTF_8))));

    assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
    assertThat(response.body()).contains("required");
    assertThat(invoked).isFalse();
  }

  @Test
  void undeclaredContentTypeIs400() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    start(Map.of("upload", invokedFlag(invoked)), b -> b);

    var response = send(upload("abc".getBytes(UTF_8)).setHeader("Content-Type", "text/plain"));

    assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
    assertThat(response.body()).contains("unsupported content type");
    assertThat(invoked).isFalse();
  }

  @Test
  void emptyBodyOnARequiredRequestBodyIs400() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    start(Map.of("upload", invokedFlag(invoked)), b -> b);

    var response = send(upload(new byte[0]));

    assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
    assertThat(response.body()).contains("request body is required");
    assertThat(invoked).isFalse();
  }

  @Test
  void emptyBodyOnAnOptionalRequestBodyReachesTheHandler() throws Exception {
    start(Map.of("optionalUpload", counting()), b -> b);

    var response =
        send(HttpRequest.newBuilder().uri(uri("/optional-uploads")).POST(BodyPublishers.noBody()));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("0");
  }

  @Test
  void securityRejectsBeforeTheHandlerRuns() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    start(Map.of("secureUpload", invokedFlag(invoked)), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/secure-uploads"))
                .header("Content-Type", OCTET_STREAM)
                .POST(BodyPublishers.ofByteArray(new byte[1024])));

    assertThat(response.statusCode()).isEqualTo(HTTP_UNAUTHORIZED);
    assertThat(invoked).isFalse();
  }

  @Test
  void authenticatedRequestKeepsItsStream() throws Exception {
    start(Map.of("secureUpload", counting()), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/secure-uploads"))
                .header("Content-Type", OCTET_STREAM)
                .header("X-API-Key", "secret")
                .POST(BodyPublishers.ofByteArray(new byte[4096])));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("4096");
  }

  @Test
  void handlerThatLeavesTheBodyUnreadStillResponds() throws Exception {
    start(Map.of("upload", (StreamingRequestHandler) request -> Response.noContent()), b -> b);

    var response = send(upload(new byte[16 * 1024]));

    assertThat(response.statusCode()).isEqualTo(204);
  }

  @Test
  void interceptorsWrapAStreamingHandler() throws Exception {
    AtomicReference<Boolean> streaming = new AtomicReference<>();
    start(
        Map.of("upload", counting()),
        b ->
            b.interceptor(
                (request, next) -> {
                  streaming.set(request.isStreaming());
                  return next.proceed();
                }));

    var response = send(upload("abc".getBytes(UTF_8)));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(streaming.get()).isTrue();
  }

  @Test
  void extraRouteStreamsItsBody() throws Exception {
    start(Map.of(), b -> b.extraRoute("/ingest", counting()));

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/ingest".formatted(server.listenPort())))
                .POST(BodyPublishers.ofByteArray(new byte[2048])));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("2048");
  }

  @Test
  void streamingExtraRouteIsHeldToTheStreamingCapWhicheverSetterComesFirst() throws Exception {
    start(
        Map.of(),
        b ->
            b.extraRoute("/ingest", counting())
                .maxDecompressedStreamingRequestBytes(1024)
                .maxDecompressedRequestBytes(1024 * 1024));
    URI ingest = URI.create("http://localhost:%d/ingest".formatted(server.listenPort()));

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(ingest)
                .header("Content-Encoding", "gzip")
                .POST(BodyPublishers.ofByteArray(gzip(new byte[64 * 1024]))));

    assertThat(response.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
  }

  @Test
  void streamingHandlerOnAnOperationWithoutARequestBodyFailsAtBoot() {
    OpenApiServer.Builder builder =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(handlers(Map.of("ping", counting())))
            .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
            .port(0);

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ping");
  }

  // -- Content-Type --

  @Test
  void missingContentTypeIsTakenAsOctetStream() throws Exception {
    start(Map.of(), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/uploads"))
                .header(UPLOAD_ID, "u-1")
                .POST(BodyPublishers.ofByteArray(new byte[10])));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("10");
  }

  @Test
  void anyMediaTypeMatchesAWildcardDeclaration() throws Exception {
    start(Map.of(), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/any-uploads"))
                .header("Content-Type", "image/png")
                .POST(BodyPublishers.ofByteArray(new byte[3])));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("3");
  }

  @Test
  void bufferedBodyMatchesATypeRangeAndKeepsItsMapper() throws Exception {
    start(Map.of(), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/texts"))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(BodyPublishers.ofString("ranged")));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.body()).isEqualTo("ranged");
  }

  @Test
  void typeRangeStillRejectsOtherTypes() throws Exception {
    start(Map.of(), b -> b);

    var response =
        send(
            HttpRequest.newBuilder()
                .uri(uri("/texts"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("\"x\"")));

    assertThat(response.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
  }

  // -- headers --

  @Test
  void handlerCanListEveryHeader() throws Exception {
    AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  headers.set(request.headers());
                  drain(request);
                  return Response.ok();
                }),
        b -> b);

    var response =
        send(upload("abc".getBytes(UTF_8)).header("X-Tag", "one").header("X-Tag", "two"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(headers.get().get("x-tag")).containsExactly("one", "two");
    assertThat(headers.get().get("upload-id")).containsExactly("u-1");
    assertThat(headers.get()).containsKey("Content-Length");
  }

  @Test
  void decodedBodyHidesItsCodingFromTheHeaderList() throws Exception {
    AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  headers.set(request.headers());
                  drain(request);
                  return Response.ok();
                }),
        b -> b);

    var response = send(upload(gzip("abc".getBytes(UTF_8))).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(headers.get()).doesNotContainKeys("Content-Encoding", "Content-Length");
  }

  // -- raw bodies --

  @Test
  void rawHandlerReadsTheCodedBodyUncapped() throws Exception {
    byte[] coded = gzip(new byte[64 * 1024]);
    AtomicReference<byte[]> body = new AtomicReference<>();
    AtomicReference<Optional<String>> encoding = new AtomicReference<>();
    start(
        Map.of(
            "upload",
            StreamingRequestHandler.raw(
                request -> {
                  body.set(drain(request));
                  encoding.set(request.header("Content-Encoding"));
                  return Response.ok();
                })),
        b -> b.maxDecompressedRequestBytes(1024));

    var response = send(upload(coded).header("Content-Encoding", "gzip"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(Arrays.equals(body.get(), coded)).isTrue();
    assertThat(encoding.get()).hasValue("gzip");
  }

  @Test
  void rawHandlerAcceptsACodingTheServerDoesNotKnow() throws Exception {
    start(Map.of("upload", StreamingRequestHandler.raw(counting())), b -> b);

    var response = send(upload("abc".getBytes(UTF_8)).header("Content-Encoding", "br"));

    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(response.headers().firstValue(RECEIVED)).hasValue("3");
  }

  // -- concurrency limit --

  @Test
  void requestOverTheStreamingLimitIs503WithRetryAfter() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    AtomicBoolean secondInvoked = new AtomicBoolean();
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  if (request.header(UPLOAD_ID).orElseThrow().equals("u-1")) {
                    entered.countDown();
                    await(proceed);
                  } else {
                    secondInvoked.set(true);
                  }
                  drain(request);
                  return Response.ok();
                }),
        b -> b.maxConcurrentStreamingRequests(1, Duration.ofMillis(2500)));

    try (HttpClient client = client()) {
      CompletableFuture<HttpResponse<String>> first =
          client.sendAsync(upload("abc".getBytes(UTF_8)).build(), BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      var rejected =
          client.send(
              upload("def".getBytes(UTF_8)).setHeader(UPLOAD_ID, "u-2").build(),
              BodyHandlers.ofString());

      assertThat(rejected.statusCode()).isEqualTo(503);
      assertThat(rejected.headers().firstValue("Retry-After")).hasValue("3");
      assertThat(rejected.headers().firstValue("Content-Type"))
          .hasValue("application/problem+json");
      assertThat(secondInvoked).isFalse();

      proceed.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(HTTP_OK);
    }

    var after = send(upload("ghi".getBytes(UTF_8)).setHeader(UPLOAD_ID, "u-3"));
    assertThat(after.statusCode()).isEqualTo(HTTP_OK);
    assertThat(secondInvoked).isTrue();
  }

  @Test
  void rejectedRequestsGiveTheirSlotBack() throws Exception {
    start(Map.of(), b -> b.maxConcurrentStreamingRequests(1));

    for (int i = 0; i < 3; i++) {
      var invalid =
          send(
              HttpRequest.newBuilder()
                  .uri(uri("/uploads"))
                  .header("Content-Type", OCTET_STREAM)
                  .POST(BodyPublishers.ofByteArray(new byte[1])));
      assertThat(invalid.statusCode()).isEqualTo(HTTP_BAD_REQUEST);
      var unauthenticated =
          send(
              HttpRequest.newBuilder()
                  .uri(uri("/secure-uploads"))
                  .header("Content-Type", OCTET_STREAM)
                  .POST(BodyPublishers.ofByteArray(new byte[1])));
      assertThat(unauthenticated.statusCode()).isEqualTo(HTTP_UNAUTHORIZED);
    }

    assertThat(send(upload(new byte[1])).statusCode()).isEqualTo(HTTP_OK);
  }

  @Test
  void bufferedRequestsAreNotLimited() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  entered.countDown();
                  await(proceed);
                  drain(request);
                  return Response.ok();
                }),
        b -> b.maxConcurrentStreamingRequests(1));

    try (HttpClient client = client()) {
      CompletableFuture<HttpResponse<String>> upload =
          client.sendAsync(upload("abc".getBytes(UTF_8)).build(), BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      var buffered =
          client.send(
              HttpRequest.newBuilder()
                  .uri(uri("/texts"))
                  .header("Content-Type", "text/plain")
                  .POST(BodyPublishers.ofString("still served"))
                  .build(),
              BodyHandlers.ofString());

      assertThat(buffered.statusCode()).isEqualTo(HTTP_OK);
      proceed.countDown();
      assertThat(upload.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(HTTP_OK);
    }
  }

  @Test
  void streamingExtraRoutesShareTheLimit() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  entered.countDown();
                  await(proceed);
                  drain(request);
                  return Response.ok();
                }),
        b -> b.maxConcurrentStreamingRequests(1).extraRoute("/ingest", counting()));

    try (HttpClient client = client()) {
      CompletableFuture<HttpResponse<String>> upload =
          client.sendAsync(upload("abc".getBytes(UTF_8)).build(), BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      var extra =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create("http://localhost:%d/ingest".formatted(server.listenPort())))
                  .POST(BodyPublishers.ofByteArray(new byte[8]))
                  .build(),
              BodyHandlers.ofString());

      assertThat(extra.statusCode()).isEqualTo(503);
      proceed.countDown();
      assertThat(upload.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(HTTP_OK);
    }

    var extra =
        send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/ingest".formatted(server.listenPort())))
                .POST(BodyPublishers.ofByteArray(new byte[8])));
    assertThat(extra.statusCode()).isEqualTo(HTTP_OK);
  }

  @Test
  void clientSlowToSendItsBodyHoldsNoSlot() throws Exception {
    start(Map.of(), b -> b.maxConcurrentStreamingRequests(1));

    try (Socket stalled = new Socket("localhost", server.listenPort())) {
      OutputStream out = stalled.getOutputStream();
      out.write(
          """
          POST /api/v1/uploads HTTP/1.1\r
          Host: localhost\r
          Content-Type: application/octet-stream\r
          Upload-Id: stalled\r
          Content-Length: 10\r
          \r
          """
              .getBytes(UTF_8));
      out.flush();
      // No server-side signal exists for a request stuck before its handler; give it time to
      // reach the peek so the upload below is the second one in.
      Thread.sleep(200); // NOSONAR java:S2925

      var response = send(upload(new byte[4]));

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    }
  }

  @Test
  void unauthenticatedClientSlowToSendItsBodyHoldsNoSlot() throws Exception {
    start(Map.of(), b -> b.maxConcurrentStreamingRequests(1));

    try (Socket stalled = new Socket("localhost", server.listenPort())) {
      OutputStream out = stalled.getOutputStream();
      out.write(
          """
          POST /api/v1/secure-uploads HTTP/1.1\r
          Host: localhost\r
          Content-Type: application/octet-stream\r
          Content-Length: 10\r
          \r
          """
              .getBytes(UTF_8));
      out.flush();
      // No server-side signal exists for a request stuck before its handler; give it time to
      // reach the peek so the upload below is the second one in.
      Thread.sleep(200); // NOSONAR java:S2925

      var response =
          send(
              HttpRequest.newBuilder()
                  .uri(uri("/secure-uploads"))
                  .header("Content-Type", OCTET_STREAM)
                  .header("X-API-Key", "secret")
                  .POST(BodyPublishers.ofByteArray(new byte[4])));

      assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    }
  }

  @Test
  void failingHandlerGivesItsSlotBack() throws Exception {
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  if (request.header(UPLOAD_ID).orElseThrow().equals("boom")) {
                    throw new IllegalStateException("boom");
                  }
                  drain(request);
                  return Response.ok();
                }),
        b -> b.maxConcurrentStreamingRequests(1));

    for (int i = 0; i < 3; i++) {
      var failed = send(upload(new byte[4]).setHeader(UPLOAD_ID, "boom"));
      assertThat(failed.statusCode()).isEqualTo(500);
    }

    assertThat(send(upload(new byte[4])).statusCode()).isEqualTo(HTTP_OK);
  }

  @Test
  void failureWhileReadingGivesTheSlotBack() throws Exception {
    start(Map.of(), b -> b.maxConcurrentStreamingRequests(1).maxDecompressedRequestBytes(1024));

    for (int i = 0; i < 3; i++) {
      var tooLarge = send(upload(gzip(new byte[64 * 1024])).header("Content-Encoding", "gzip"));
      assertThat(tooLarge.statusCode()).isEqualTo(HTTP_ENTITY_TOO_LARGE);
    }

    assertThat(send(upload(new byte[4])).statusCode()).isEqualTo(HTTP_OK);
  }

  @Test
  void failingStreamingExtraGivesItsSlotBack() throws Exception {
    start(
        Map.of(),
        b ->
            b.maxConcurrentStreamingRequests(1)
                .extraRoute(
                    "/ingest",
                    (StreamingRequestHandler)
                        request -> {
                          throw new IllegalStateException("boom");
                        }));

    for (int i = 0; i < 3; i++) {
      var failed =
          send(
              HttpRequest.newBuilder()
                  .uri(URI.create("http://localhost:%d/ingest".formatted(server.listenPort())))
                  .POST(BodyPublishers.ofByteArray(new byte[4])));
      assertThat(failed.statusCode()).isEqualTo(500);
    }

    assertThat(send(upload(new byte[4])).statusCode()).isEqualTo(HTTP_OK);
  }

  @Test
  void afterResponseHooksSeeARejectedRequest() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    List<Integer> statuses = new CopyOnWriteArrayList<>();
    CountDownLatch hooked = new CountDownLatch(2);
    start(
        Map.of(
            "upload",
            (StreamingRequestHandler)
                request -> {
                  entered.countDown();
                  await(proceed);
                  drain(request);
                  return Response.ok();
                }),
        b ->
            b.maxConcurrentStreamingRequests(1)
                .afterResponseHook(
                    (request, response) -> {
                      statuses.add(response.status());
                      hooked.countDown();
                    }));

    try (HttpClient client = client()) {
      CompletableFuture<HttpResponse<String>> first =
          client.sendAsync(upload("abc".getBytes(UTF_8)).build(), BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(client.send(upload(new byte[1]).build(), BodyHandlers.ofString()).statusCode())
          .isEqualTo(503);

      proceed.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(HTTP_OK);
    }

    assertThat(hooked.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(statuses).containsExactlyInAnyOrder(503, HTTP_OK);
  }

  // -- helpers --

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .executor(newVirtualThreadPerTaskExecutor())
        .version(HTTP_1_1)
        .build();
  }

  private void start(
      Map<String, RequestHandler> overrides, UnaryOperator<OpenApiServer.Builder> customise)
      throws IOException {
    server =
        customise
            .apply(
                OpenApiServer.builder()
                    .spec(spec)
                    .handlers(handlers(overrides))
                    .securityValidator(
                        "apiKeyAuth",
                        (req, cred) ->
                            cred instanceof ApiKeyCredential(String value) && value.equals("secret")
                                ? Optional.of(value)
                                : Optional.empty())
                    .port(0))
            .build();
  }

  private Map<String, RequestHandler> handlers(Map<String, RequestHandler> overrides) {
    Map<String, RequestHandler> all = new HashMap<>();
    all.put("upload", counting());
    all.put("optionalUpload", counting());
    all.put("secureUpload", counting());
    all.put("ping", request -> Response.ok());
    all.put("anyUpload", counting());
    all.put("textRange", request -> Response.text(HTTP_OK, (String) request.parsed()));
    all.putAll(overrides);
    return all;
  }

  /** Reads the whole body and reports how many bytes arrived. */
  private static StreamingRequestHandler counting() {
    return request -> Response.ok().withHeader(RECEIVED, String.valueOf(drain(request).length));
  }

  private static StreamingRequestHandler invokedFlag(AtomicBoolean invoked) {
    return request -> {
      invoked.set(true);
      drain(request);
      return Response.ok();
    };
  }

  private static byte[] drain(Request request) {
    try (InputStream in = request.bodyStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private HttpRequest.Builder upload(byte[] body) {
    return HttpRequest.newBuilder()
        .uri(uri("/uploads"))
        .header("Content-Type", OCTET_STREAM)
        .header(UPLOAD_ID, "u-1")
        .POST(BodyPublishers.ofByteArray(body));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:%d/api/v1%s".formatted(server.listenPort(), path));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    try (HttpClient client = client()) {
      return client.send(request.build(), BodyHandlers.ofString());
    }
  }

  private static byte[] gzip(byte[] plain) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(plain);
    }
    return out.toByteArray();
  }

  private static Spec loadSpec() {
    try (InputStream in =
        StreamingRequestBodyIT.class.getResourceAsStream("/streaming-openapi.json")) {
      return Spec.fromJson(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
