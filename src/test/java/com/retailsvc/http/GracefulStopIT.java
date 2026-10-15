package com.retailsvc.http;

import static com.retailsvc.http.ServerBaseTest.stubAllHandlers;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.retailsvc.http.spec.Spec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OpenApiServer#stop(int)}: it waits for requests in flight and for nothing else, refuses
 * new connections at once and serves what is already on an open connection.
 */
class GracefulStopIT {

  private static final String OPERATION_ID = "get-data";
  private static final String DATA_PATH = "/api/v1/data";
  private static final String ALIVE_PATH = "/alive";
  private static final Spec SPEC = loadSpec();
  private static final int STOP_DELAY_SECONDS = 2;
  private static final long PROMPT_MILLIS = 1_000;
  private static final int ROUNDS = 100;
  private static final long MAX_PARK_NANOS = 400_000;
  private static final long LONG_ENOUGH_MILLIS = 300;

  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch entered = new CountDownLatch(1);
  private final HttpClient client = HttpClient.newHttpClient();
  private OpenApiServer server;

  @AfterEach
  void releaseAndStop() {
    release.countDown();
    if (server != null) {
      server.stop(0);
    }
    client.close();
  }

  /**
   * The main guard: a connection that is accepted and dropped as {@code stop} is called used to
   * leave the JDK waiting out the whole delay, in about one round in four.
   */
  @Test
  void stopsPromptlyAfterAClientDropsItsConnection() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      try (var started = start(Map.of())) {
        try (var socket = new Socket()) {
          socket.connect(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), started.listenPort()));
        }
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(MAX_PARK_NANOS));

        assertThat(stopMillis(started)).as("round %d", round).isLessThan(PROMPT_MILLIS);
      }
    }
  }

  /** The same race through a real client; it hits far less often, so it only backs up the above. */
  @Test
  void stopsPromptlyAfterAClientClosesItsKeepAliveConnection() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      try (var started = start(Map.of())) {
        try (var own = HttpClient.newHttpClient()) {
          assertThat(own.send(get(started, ALIVE_PATH), BodyHandlers.discarding()).statusCode())
              .isEqualTo(HTTP_NO_CONTENT);
        }

        assertThat(stopMillis(started)).as("round %d", round).isLessThan(PROMPT_MILLIS);
      }
    }
  }

  @Test
  void waitsForARequestInFlightAndNotLonger() throws Exception {
    server = startBlockingOnData();
    var response = sendBlocking();
    assertThat(server.inFlightRequests()).isEqualTo(1);

    var stopping = CompletableFuture.supplyAsync(() -> stopMillis(server));
    assertThat(isDone(stopping, LONG_ENOUGH_MILLIS)).isFalse();
    release.countDown();

    assertThat(stopping.get(5, TimeUnit.SECONDS)).isLessThan(STOP_DELAY_SECONDS * 1_000L);
    assertThat(response.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(HTTP_OK);
    assertThat(server.inFlightRequests()).isZero();
  }

  @Test
  void givesUpOnARequestInFlightAtTheDelay() throws Exception {
    server = startBlockingOnData();
    sendBlocking();

    long millis = stopMillis(server, 1);

    assertThat(millis).isBetween(900L, 1_000L + PROMPT_MILLIS);
  }

  @Test
  void keepsEveryStopWaitingWhileTheFirstDrains() throws Exception {
    server = startBlockingOnData();
    sendBlocking();

    var first = CompletableFuture.runAsync(() -> server.stop(STOP_DELAY_SECONDS));
    var second = CompletableFuture.runAsync(() -> server.stop(STOP_DELAY_SECONDS));

    assertThat(isDone(first, LONG_ENOUGH_MILLIS)).isFalse();
    assertThat(isDone(second, LONG_ENOUGH_MILLIS)).isFalse();
    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    second.get(5, TimeUnit.SECONDS);
    assertThat(stopMillis(server)).isLessThan(PROMPT_MILLIS);
  }

  @Test
  void refusesNewConnectionsWhileDraining() throws Exception {
    server = startBlockingOnData();
    sendBlocking();
    var stopping = CompletableFuture.runAsync(() -> server.stop(STOP_DELAY_SECONDS));

    assertThat(eventually(() -> !accepts(server.listenPort()))).isTrue();

    assertThat(server.inFlightRequests()).isEqualTo(1);
    release.countDown();
    stopping.get(5, TimeUnit.SECONDS);
  }

  @Test
  void servesAnOpenKeepAliveConnectionWhileDraining() throws Exception {
    server = startBlockingOnData();
    try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.listenPort());
        var in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      assertThat(roundTrip(socket, in)).isEqualTo(HTTP_NO_CONTENT);
      sendBlocking();
      var stopping = CompletableFuture.runAsync(() -> server.stop(STOP_DELAY_SECONDS));
      assertThat(eventually(() -> !accepts(server.listenPort()))).isTrue();

      assertThat(roundTrip(socket, in)).isEqualTo(HTTP_NO_CONTENT);

      release.countDown();
      stopping.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void countsBackToZeroWhenAHandlerThrows() throws Exception {
    server =
        start(
            Map.of(
                OPERATION_ID,
                _ -> {
                  throw new IllegalStateException("boom");
                }));

    var response = client.send(get(server, DATA_PATH), BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(HTTP_INTERNAL_ERROR);
    assertThat(eventually(() -> server.inFlightRequests() == 0)).isTrue();
  }

  @Test
  void waitsForAfterResponseRunnables() throws Exception {
    var ran = new CountDownLatch(1);
    server =
        start(
            Map.of(
                OPERATION_ID,
                req -> {
                  req.afterResponse(
                      () -> {
                        ran.countDown();
                        awaitRelease();
                      });
                  return Response.status(HTTP_OK);
                }));

    var response = client.send(get(server, DATA_PATH), BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(HTTP_OK);
    assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(server.inFlightRequests()).isEqualTo(1);

    var stopping = CompletableFuture.supplyAsync(() -> stopMillis(server));
    assertThat(isDone(stopping, LONG_ENOUGH_MILLIS)).isFalse();
    release.countDown();

    assertThat(stopping.get(5, TimeUnit.SECONDS)).isLessThan(STOP_DELAY_SECONDS * 1_000L);
    assertThat(server.inFlightRequests()).isZero();
  }

  @Test
  void keepsTheInterruptFlagOfAThreadInterruptedWhileStopping() throws Exception {
    server = startBlockingOnData();
    sendBlocking();
    var flagAfterStop = new AtomicBoolean();
    var stopping =
        Thread.ofPlatform()
            .start(
                () -> {
                  server.stop(STOP_DELAY_SECONDS);
                  flagAfterStop.set(Thread.currentThread().isInterrupted());
                });
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(LONG_ENOUGH_MILLIS));

    stopping.interrupt();
    stopping.join(5_000);

    assertThat(stopping.isAlive()).isFalse();
    assertThat(flagAfterStop).isTrue();
  }

  @Test
  void ignoresASecondStop() {
    server = start(Map.of());

    server.stop(0);

    assertThat(stopMillis(server)).isLessThan(PROMPT_MILLIS);
  }

  /** A server whose {@code get-data} handler waits for {@link #release}. */
  private OpenApiServer startBlockingOnData() {
    return start(
        Map.of(
            OPERATION_ID,
            _ -> {
              entered.countDown();
              awaitRelease();
              return Response.status(HTTP_OK);
            }));
  }

  /** Sends the request that blocks and returns once its handler runs. */
  private CompletableFuture<HttpResponse<Void>> sendBlocking() throws InterruptedException {
    var response = client.sendAsync(get(server, DATA_PATH), BodyHandlers.discarding());
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    return response;
  }

  private OpenApiServer start(Map<String, RequestHandler> overrides) {
    try {
      return OpenApiServer.builder()
          .spec(SPEC)
          .handlers(stubAllHandlers(SPEC, overrides))
          .extraRoute(ALIVE_PATH, Handlers.aliveHandler())
          .port(0)
          .securityValidator("apiKeyAuth", (_, _) -> Optional.empty())
          .securityValidator("bearerAuth", (_, _) -> Optional.empty())
          .securityValidator("basicAuth", (_, _) -> Optional.empty())
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpRequest get(OpenApiServer server, String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + server.listenPort() + path))
        .GET()
        .build();
  }

  /** Sends a keep-alive {@code GET /alive} on the socket and returns the status it is answered. */
  private static int roundTrip(Socket socket, BufferedReader in) throws IOException {
    socket
        .getOutputStream()
        .write(
            ("GET " + ALIVE_PATH + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    int status = Integer.parseInt(in.readLine().split(" ")[1]);
    String line;
    do {
      line = in.readLine();
    } while (line != null && !line.isEmpty());
    return status;
  }

  private static boolean accepts(int port) {
    try (var _ = new Socket(InetAddress.getLoopbackAddress(), port)) {
      return true;
    } catch (IOException _) {
      return false;
    }
  }

  private static long stopMillis(OpenApiServer server) {
    return stopMillis(server, STOP_DELAY_SECONDS);
  }

  private static long stopMillis(OpenApiServer server, int delaySeconds) {
    long start = System.nanoTime();
    server.stop(delaySeconds);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private void awaitRelease() {
    try {
      release.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isDone(CompletableFuture<?> future, long millis)
      throws InterruptedException {
    try {
      future.get(millis, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException _) {
      return false;
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Polls {@code condition} for up to five seconds. */
  private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(10);
    }
    return condition.getAsBoolean();
  }

  private static Spec loadSpec() {
    try (InputStream in = GracefulStopIT.class.getResourceAsStream("/openapi.json")) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) new Gson().fromJson(text, Map.class);
      return Spec.from(raw);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
