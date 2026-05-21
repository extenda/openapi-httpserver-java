package com.retailsvc.http;

import static com.retailsvc.http.ServerBaseTest.stubAllHandlers;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.spec.Spec;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AfterResponseHookIT {

  // get-data accepts GET /data with no required parameters
  private static final String OK_OPERATION_ID = "get-data";
  private static final String OK_PATH = "/api/v1/data";
  private static final String NOT_FOUND_PATH = "/api/v1/does-not-exist";

  private static final Spec SPEC = loadSpec();

  private static Spec loadSpec() {
    Gson gson = new Gson();
    try (InputStream in = AfterResponseHookIT.class.getResourceAsStream("/openapi.json")) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) gson.fromJson(text, Map.class);
      return Spec.from(raw);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static OpenApiServer.Builder baseBuilder() {
    return OpenApiServer.builder()
        .spec(SPEC)
        .port(0)
        .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
        .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
        .securityValidator("basicAuth", (req, cred) -> Optional.empty());
  }

  private static URI uri(OpenApiServer server, String path) {
    return URI.create("http://localhost:" + server.listenPort() + path);
  }

  @Test
  void globalHookFiresAfterSuccessfulResponse() throws Exception {
    AtomicReference<Request> capturedRequest = new AtomicReference<>();
    AtomicReference<Response> capturedResponse = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC, Map.of(OK_OPERATION_ID, req -> Response.status(HTTP_NO_CONTENT))))
            .afterResponseHook(
                (req, resp) -> {
                  capturedRequest.set(req);
                  capturedResponse.set(resp);
                  latch.countDown();
                })
            .build()) {

      HttpResponse<Void> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(HTTP_NO_CONTENT);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(capturedRequest.get()).isNotNull();
      assertThat(capturedRequest.get().operationId()).isEqualTo(OK_OPERATION_ID);
      assertThat(capturedResponse.get()).isNotNull();
      assertThat(capturedResponse.get().status()).isEqualTo(HTTP_NO_CONTENT);
    }
  }

  @Test
  void perRequestRunnablesFireInOrder() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC,
                    Map.of(
                        OK_OPERATION_ID,
                        req -> {
                          req.afterResponse(
                              () -> {
                                log.add("first");
                                latch.countDown();
                              });
                          req.afterResponse(
                              () -> {
                                log.add("second");
                                latch.countDown();
                              });
                          return Response.status(HTTP_NO_CONTENT);
                        })))
            .build()) {

      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
              BodyHandlers.discarding());

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(log).containsExactly("first", "second");
    }
  }

  @Test
  void hookExceptionDoesNotAffectClientOrOtherHooks() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC, Map.of(OK_OPERATION_ID, req -> Response.status(HTTP_NO_CONTENT))))
            .afterResponseHook(
                (req, resp) -> {
                  throw new RuntimeException("boom");
                })
            .afterResponseHook(
                (req, resp) -> {
                  log.add("second-ran");
                  latch.countDown();
                })
            .build()) {

      HttpResponse<Void> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(HTTP_NO_CONTENT);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(log).containsExactly("second-ran");
    }
  }

  @Test
  void hookFiresOnHandlerException() throws Exception {
    AtomicReference<Response> capturedResponse = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC,
                    Map.of(
                        OK_OPERATION_ID,
                        req -> {
                          throw new RuntimeException("kapow");
                        })))
            .afterResponseHook(
                (req, resp) -> {
                  capturedResponse.set(resp);
                  latch.countDown();
                })
            .build()) {

      HttpResponse<Void> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(HTTP_INTERNAL_ERROR);
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(capturedResponse.get()).isNotNull();
      assertThat(capturedResponse.get().status()).isEqualTo(HTTP_INTERNAL_ERROR);
      assertThat(capturedResponse.get().body()).isNull();
    }
  }

  @Test
  void preRequestFailureSkipsHooks() throws Exception {
    List<String> log = new CopyOnWriteArrayList<>();

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC, Map.of(OK_OPERATION_ID, req -> Response.status(HTTP_NO_CONTENT))))
            .afterResponseHook((req, resp) -> log.add("fired"))
            .build()) {

      HttpResponse<Void> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(uri(server, NOT_FOUND_PATH)).GET().build(),
                  BodyHandlers.discarding());

      assertThat(resp.statusCode()).isEqualTo(HTTP_NOT_FOUND);
      assertThat(log).isEmpty();
    }
  }

  @Test
  void hookSeesScopedRequestAndSameThreadAsHandler() throws Exception {
    AtomicReference<Request> hookScopedRequest = new AtomicReference<>();
    AtomicReference<Thread> handlerThread = new AtomicReference<>();
    AtomicReference<Thread> hookThread = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (OpenApiServer server =
        baseBuilder()
            .handlers(
                stubAllHandlers(
                    SPEC,
                    Map.of(
                        OK_OPERATION_ID,
                        req -> {
                          handlerThread.set(Thread.currentThread());
                          return Response.status(HTTP_NO_CONTENT);
                        })))
            .afterResponseHook(
                (req, resp) -> {
                  hookScopedRequest.set(DispatchHandler.CURRENT.get());
                  hookThread.set(Thread.currentThread());
                  latch.countDown();
                })
            .build()) {

      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(uri(server, OK_PATH)).GET().build(),
              BodyHandlers.discarding());

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(hookScopedRequest.get()).isNotNull();
      assertThat(hookScopedRequest.get().operationId()).isEqualTo(OK_OPERATION_ID);
      assertThat(hookThread.get()).isSameAs(handlerThread.get());
    }
  }
}
