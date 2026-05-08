package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.validate.ValidationError;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HandlersDefaultExceptionTest {
  private HttpExchange newExchange(ByteArrayOutputStream sink) {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getResponseHeaders()).thenReturn(new Headers());
    Mockito.when(ex.getResponseBody()).thenReturn(sink);
    return ex;
  }

  @Test
  void validationExceptionRendersProblem() throws Exception {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    HttpExchange ex = newExchange(sink);

    Handlers.defaultExceptionHandler()
        .handle(
            ex,
            new ValidationException(new ValidationError("/x", "type", "expected string", null)));

    Mockito.verify(ex).sendResponseHeaders(Mockito.eq(400), Mockito.anyLong());
    assertThat(ex.getResponseHeaders().getFirst("Content-Type"))
        .isEqualTo("application/problem+json");
    assertThat(sink.toString()).contains("\"keyword\":\"type\"");
  }

  @Test
  void notFoundReturns404() throws Exception {
    HttpExchange ex = newExchange(new ByteArrayOutputStream());
    Handlers.defaultExceptionHandler().handle(ex, new NotFoundException("GET /x"));
    Mockito.verify(ex).sendResponseHeaders(404, 0);
  }

  @Test
  void methodNotAllowedReturns405WithAllowHeader() throws Exception {
    HttpExchange ex = newExchange(new ByteArrayOutputStream());
    Handlers.defaultExceptionHandler()
        .handle(ex, new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST)));
    Mockito.verify(ex).sendResponseHeaders(405, 0);
    assertThat(ex.getResponseHeaders().getFirst("Allow")).contains("GET").contains("POST");
  }
}
