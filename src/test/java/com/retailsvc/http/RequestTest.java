package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RequestTest {
  @Test
  void readsAttributes() {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    Mockito.when(ex.getAttribute("body")).thenReturn(new byte[] {1, 2, 3});
    Mockito.when(ex.getAttribute("parsed-body")).thenReturn(Map.of("k", "v"));
    Mockito.when(ex.getAttribute("operation-id")).thenReturn("get-x");
    Mockito.when(ex.getAttribute("path-parameters")).thenReturn(Map.of("id", "42"));

    assertThat(Request.bytes(ex)).containsExactly(1, 2, 3);
    assertThat(Request.parsed(ex)).isEqualTo(Map.of("k", "v"));
    assertThat(Request.operationId(ex)).isEqualTo("get-x");
    assertThat(Request.pathParams(ex)).containsEntry("id", "42");
  }
}
