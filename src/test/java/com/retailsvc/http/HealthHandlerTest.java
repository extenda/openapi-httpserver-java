package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HealthHandlerTest {

  @Test
  void getReturns200AndJsonBodyWhenUp() throws IOException {
    HealthOutcome outcome = new HealthOutcome("Up", List.of(new Dependency("jdbc", "Up")));
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(() -> outcome).handle(ex);

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

    Handlers.healthHandler(() -> new HealthOutcome("Up", List.of())).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_OK), eq((long) body.size()));
    assertThat(body.toString()).isEqualTo("{\"outcome\":\"Up\",\"dependencies\":[]}");
  }

  @Test
  void getReturns503WhenDown() throws IOException {
    HealthOutcome outcome = new HealthOutcome("Down", List.of(new Dependency("jdbc", "Down")));
    HttpExchange ex = newExchange("GET");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.healthHandler(() -> outcome).handle(ex);

    verify(ex).sendResponseHeaders(eq(HTTP_UNAVAILABLE), eq((long) body.size()));
    assertThat(headers.getFirst("Content-Type")).isEqualTo("application/json");
    assertThat(body.toString()).contains("\"outcome\":\"Down\"");
  }

  @Test
  void headIsAccepted() throws IOException {
    HttpExchange ex = newExchange("HEAD");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    Handlers.healthHandler(() -> new HealthOutcome("Up", List.of())).handle(ex);

    verify(ex)
        .sendResponseHeaders(
            eq(HTTP_OK), eq((long) "{\"outcome\":\"Up\",\"dependencies\":[]}".length()));
  }

  @Test
  void postReturns405WithAllowHeader() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);

    Handlers.healthHandler(() -> new HealthOutcome("Up", List.of())).handle(ex);

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
    Handlers.healthHandler(failing).handle(ex);

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

    Handlers.healthHandler(() -> null).handle(ex);

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
