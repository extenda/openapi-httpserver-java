package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.internal.gson.GsonJsonMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HealthHandlerTest {

  private static final TypeMapper JSON = new GsonJsonMapper();

  @Test
  void getReturns200AndJsonBodyWhenUp() throws IOException {
    HealthOutcome outcome = new HealthOutcome(true, List.of(new Dependency("jdbc", true)));
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(JSON, () -> outcome).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_OK), eq((long) body.size()));
    assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
    assertThat(body.toString())
        .isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[{\"id\":\"jdbc\",\"status\":\"Up\"}]}");
  }

  @Test
  void getReturns200WithEmptyDependencyArrayWhenNoDeps() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of())).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_OK), eq((long) body.size()));
    assertThat(body.toString()).isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[]}");
  }

  @Test
  void getReturns503WhenDown() throws IOException {
    HealthOutcome outcome = new HealthOutcome(false, List.of(new Dependency("jdbc", false)));
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(JSON, () -> outcome).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_UNAVAILABLE), eq((long) body.size()));
    assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
    assertThat(body.toString())
        .isEqualTo(
            "{\"outcome\":\"Down\",\"dependencies\":[{\"id\":\"jdbc\",\"status\":\"Down\"}]}");
  }

  @Test
  void headIsAccepted() throws IOException {
    HttpExchange ex = newExchange("HEAD");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of())).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_OK), eq((long) body.size()));
  }

  @Test
  void postReturns405WithAllowHeader() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);

    Handlers.healthHandler(JSON, () -> new HealthOutcome(true, List.of())).handle(ex);

    verify(ex).sendResponseHeaders(HTTP_BAD_METHOD, -1);
    assertThat(headers.getFirst("Allow")).isEqualTo("GET, HEAD");
  }

  @Test
  void runtimeExceptionFromProbeMapsToDown503() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Supplier<HealthOutcome> failing =
        () -> {
          throw new IllegalStateException("boom");
        };
    Handlers.healthHandler(JSON, failing).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_UNAVAILABLE), eq((long) body.size()));
    assertThat(body.toString()).isEqualTo("{\"outcome\":\"Down\",\"dependencies\":[]}");
  }

  @Test
  void nullReturnFromProbeMapsToDown503() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(JSON, () -> null).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_UNAVAILABLE), eq((long) body.size()));
    assertThat(body.toString()).isEqualTo("{\"outcome\":\"Down\",\"dependencies\":[]}");
  }

  private static HttpExchange newExchange(String method) {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn(method);
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    return ex;
  }
}
