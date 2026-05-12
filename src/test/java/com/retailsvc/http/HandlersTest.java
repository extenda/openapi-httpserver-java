package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class HandlersTest {

  @Test
  void aliveHandlerReturns204OnGet() throws IOException {
    HttpExchange ex = newExchange("GET");
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(204, -1);
  }

  @Test
  void aliveHandlerReturns204OnHead() throws IOException {
    HttpExchange ex = newExchange("HEAD");
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(204, -1);
  }

  @Test
  void aliveHandlerReturns405OnPost() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);
    Handlers.aliveHandler().handle(ex);
    verify(ex).sendResponseHeaders(405, -1);
    assertThat(headers.getFirst("Allow")).isEqualTo("GET, HEAD");
  }

  @Test
  void specHandlerServesYamlWithInferredContentType() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);

    Handlers.specHandler("/openapi.yaml").handle(ex);

    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/yaml");
    verify(ex)
        .sendResponseHeaders(
            org.mockito.ArgumentMatchers.eq(200),
            org.mockito.ArgumentMatchers.longThat(n -> n > 0));
    assertThat(body.toByteArray()).isNotEmpty();
  }

  @Test
  void specHandlerInfersJsonContentType() throws IOException {
    HttpExchange ex = newExchange("GET");
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    Handlers.specHandler("/openapi.json").handle(ex);

    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void specHandlerThrowsAtConstructionForMissingResource() {
    assertThatThrownBy(() -> Handlers.specHandler("/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.yaml");
  }

  @Test
  void specHandlerReturns405OnPost() throws IOException {
    HttpExchange ex = newExchange("POST");
    Headers headers = new Headers();
    when(ex.getResponseHeaders()).thenReturn(headers);

    Handlers.specHandler("/openapi.yaml").handle(ex);

    verify(ex).sendResponseHeaders(405, -1);
    assertThat(headers.getFirst("Allow")).isEqualTo("GET, HEAD");
  }

  private static HttpExchange newExchange(String method) {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn(method);
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    return ex;
  }
}
