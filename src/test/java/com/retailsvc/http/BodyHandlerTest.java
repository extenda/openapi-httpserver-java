package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BodyHandlerTest {

  BodyHandler bodyHandler;
  HttpExchange exchange;
  Chain chain;

  @BeforeEach
  void setUp() {
    bodyHandler = new BodyHandler();
    exchange = mock(HttpExchange.class);
    chain = mock(Chain.class);
  }

  @Test
  void testEmptyBodyDoesNotDecorateAttributes() throws IOException {
    byte[] emptyBody = new byte[0];
    InputStream inputStream = new ByteArrayInputStream(emptyBody);

    when(exchange.getRequestBody()).thenReturn(inputStream);

    bodyHandler.doFilter(exchange, chain);

    verify(exchange, never()).setAttribute(eq("body"), any());
    verify(chain).doFilter(exchange);
  }

  @Test
  void testBodyContentsDecorateAttributes() throws IOException {
    byte[] body = "body content".getBytes();
    InputStream inputStream = new ByteArrayInputStream(body);

    when(exchange.getRequestBody()).thenReturn(inputStream);

    bodyHandler.doFilter(exchange, chain);

    verify(exchange).setAttribute("body", body);
    verify(chain).doFilter(exchange);
  }

  @Test
  void testsReturnsCorrectDescription() {
    String description = bodyHandler.description();

    assertThat(description).isEqualTo("Body handler");
  }
}
