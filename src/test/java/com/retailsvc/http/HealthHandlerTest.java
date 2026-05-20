package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.internal.gson.GsonJsonMapper;
import com.retailsvc.http.spec.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HealthHandlerTest {

  private static final TypeMapper JSON = new GsonJsonMapper();
  private static final UnaryOperator<String> NO_HEADERS = name -> null;

  private static Request request(HttpMethod method) {
    return new Request(new byte[0], null, null, null, Map.of(), null, NO_HEADERS, Map.of(), method);
  }

  @Test
  void getReturns200AndJsonBodyWhenUp() {
    HealthOutcome outcome = new HealthOutcome(true, List.of(new Dependency("jdbc", true)));

    Response resp = Handlers.healthHandler(JSON, () -> outcome).handle(request(GET));

    assertThat(resp.status()).isEqualTo(HTTP_OK);
    assertThat(resp.contentType()).isEqualTo("application/json");
    assertThat(new String((byte[]) resp.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"jdbc\",\"status\":\"Up\"}]}");
  }

  @Test
  void getReturns200WithEmptyDependencyArrayWhenNoDeps() {
    Response resp =
        Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of())).handle(request(GET));

    assertThat(resp.status()).isEqualTo(HTTP_OK);
    assertThat(new String((byte[]) resp.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[]}");
  }

  @Test
  void getReturns503WhenDown() {
    HealthOutcome outcome = new HealthOutcome(false, List.of(new Dependency("jdbc", false)));

    Response resp = Handlers.healthHandler(JSON, () -> outcome).handle(request(GET));

    assertThat(resp.status()).isEqualTo(HTTP_UNAVAILABLE);
    assertThat(resp.contentType()).isEqualTo("application/json");
    assertThat(new String((byte[]) resp.body(), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"outcome\":\"Down\",\"dependencies\":[{\"id\":\"jdbc\",\"status\":\"Down\"}]}");
  }

  @Test
  void headIsAccepted() {
    Response resp =
        Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of()))
            .handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(HTTP_OK);
  }

  @Test
  void postReturns405WithAllowHeader() {
    Response resp =
        Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of()))
            .handle(request(POST));

    assertThat(resp.status()).isEqualTo(HTTP_BAD_METHOD);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void runtimeExceptionFromProbeMapsToDown503() {
    Supplier<HealthOutcome> failing =
        () -> {
          throw new IllegalStateException("boom");
        };

    Response resp = Handlers.healthHandler(JSON, failing).handle(request(GET));

    assertThat(resp.status()).isEqualTo(HTTP_UNAVAILABLE);
    assertThat(new String((byte[]) resp.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"outcome\":\"Down\",\"dependencies\":[]}");
  }

  @Test
  void nullReturnFromProbeMapsToDown503() {
    Response resp = Handlers.healthHandler(JSON, () -> null).handle(request(GET));

    assertThat(resp.status()).isEqualTo(HTTP_UNAVAILABLE);
    assertThat(new String((byte[]) resp.body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"outcome\":\"Down\",\"dependencies\":[]}");
  }
}
