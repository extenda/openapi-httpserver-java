package com.retailsvc.http;

import static java.util.Objects.nonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.BodyHandler.RequestBodyWrapper;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BodyHandlerTest {

  private final BodyHandler bodyHandler = new BodyHandler();

  private HttpExchange exchange;
  private Chain chain;

  @BeforeEach
  void setUp() {
    exchange = mock();
    chain = mock();
  }

  @Test
  void shouldStoreBodyBytesInAttributes() throws IOException {
    byte[] expectedBytes = "test body".getBytes();
    ByteArrayInputStream inputStream = new ByteArrayInputStream(expectedBytes);
    HttpExchange mockExchange = mock();
    when(mockExchange.getRequestBody()).thenReturn(inputStream);

    BodyHandler.Chain mockChain = mock();
    BodyHandler handler = new BodyHandler();

    handler.doFilter(mockExchange, mockChain);

    verify(mockChain)
        .doFilter(
            argThat(
                ex -> {
                  byte[] storedBytes = (byte[]) ex.getAttribute(BodyHandler.BODY_ATTRIBUTE);
                  return nonNull(storedBytes) && Arrays.equals(expectedBytes, storedBytes);
                }));
  }

  private static Stream<Arguments> attributeTestCases() {
    return Stream.of(
        arguments("testKey1", "testValue"),
        arguments("testKey2", 123),
        arguments("testKey3", new byte[] {1, 2, 3}),
        arguments("nullKey", null));
  }

  @ParameterizedTest
  @MethodSource("attributeTestCases")
  void shouldHandleAttributesCorrectly(String key, Object value) {
    HttpExchange mockDelegate = mock();
    var wrapper = new BodyHandler.RequestBodyWrapper(mockDelegate, new byte[0]);

    wrapper.setAttribute(key, value);
    Object retrievedValue = wrapper.getAttribute(key);

    assertThat(retrievedValue).isEqualTo(value);
  }

  @Test
  void shouldDelegateMethodsCorrectly() throws IOException {
    HttpExchange mockDelegate = mock();
    InetSocketAddress mockAddress = new InetSocketAddress(0);
    URI mockUri = URI.create("http://test.com");
    HttpPrincipal mockPrincipal = new HttpPrincipal("test", "realm");
    HttpContext mockContext = mock();
    Headers mockRequestHeaders = mock();
    Headers mockResponseHeaders = mock();
    InputStream mockInputStream = mock();
    OutputStream mockOutputStream = mock();

    when(mockDelegate.getLocalAddress()).thenReturn(mockAddress);
    when(mockDelegate.getRemoteAddress()).thenReturn(mockAddress);
    when(mockDelegate.getRequestURI()).thenReturn(mockUri);
    when(mockDelegate.getRequestMethod()).thenReturn("POST");
    when(mockDelegate.getPrincipal()).thenReturn(mockPrincipal);
    when(mockDelegate.getProtocol()).thenReturn("HTTP/1.1");
    when(mockDelegate.getHttpContext()).thenReturn(mockContext);
    when(mockDelegate.getRequestHeaders()).thenReturn(mockRequestHeaders);
    when(mockDelegate.getResponseHeaders()).thenReturn(mockResponseHeaders);
    when(mockDelegate.getRequestBody()).thenReturn(mockInputStream);
    when(mockDelegate.getResponseBody()).thenReturn(mockOutputStream);
    when(mockDelegate.getResponseCode()).thenReturn(400);

    byte[] emptyBody = new byte[0];

    var wrapper = new BodyHandler.RequestBodyWrapper(mockDelegate, emptyBody);

    wrapper.sendResponseHeaders(200, -1);
    wrapper.setStreams(mockInputStream, mockOutputStream);
    wrapper.close();

    assertThat(wrapper.getLocalAddress()).isEqualTo(mockAddress);
    assertThat(wrapper.getRemoteAddress()).isEqualTo(mockAddress);
    assertThat(wrapper.getRequestURI()).isEqualTo(mockUri);
    assertThat(wrapper.getRequestMethod()).isEqualTo("POST");
    assertThat(wrapper.getPrincipal()).isEqualTo(mockPrincipal);
    assertThat(wrapper.getProtocol()).isEqualTo("HTTP/1.1");
    assertThat(wrapper.getHttpContext()).isEqualTo(mockContext);
    assertThat(wrapper.getRequestHeaders()).isEqualTo(mockRequestHeaders);
    assertThat(wrapper.getResponseHeaders()).isEqualTo(mockResponseHeaders);
    assertThat(wrapper.getRequestBody()).isEqualTo(mockInputStream);
    assertThat(wrapper.getResponseBody()).isEqualTo(mockOutputStream);
    assertThat(wrapper.getRequestBodyAsBytes()).isSameAs(emptyBody);
    assertThat(wrapper.getResponseCode()).isEqualTo(400);

    verify(mockDelegate).sendResponseHeaders(200, -1);
    verify(mockDelegate).setStreams(mockInputStream, mockOutputStream);
    verify(mockDelegate).close();
  }

  @Test
  void shouldHandleContextAttributesCorrectly() {
    HttpExchange mockDelegate = mock();
    var wrapper = new BodyHandler.RequestBodyWrapper(mockDelegate, new byte[0]);

    wrapper.setContextAttribute("contextKey", "contextValue");

    verify(mockDelegate).setAttribute("contextKey", "contextValue");

    when(mockDelegate.getAttribute("contextKey")).thenReturn("contextValue");
    assertThat(wrapper.getContextAttribute("contextKey")).isEqualTo("contextValue");
  }

  @Test
  void shouldReturnDescription() {
    BodyHandler handler = new BodyHandler();
    assertThat(handler.description()).isEqualTo("Body handler");
  }

  @Test
  void testEmptyBodyDoesNotDecorateAttributes() throws IOException {
    byte[] emptyBody = new byte[0];
    InputStream inputStream = new ByteArrayInputStream(emptyBody);

    when(exchange.getRequestBody()).thenReturn(inputStream);

    bodyHandler.doFilter(exchange, chain);

    verify(exchange, never()).setAttribute(eq("body"), any());
    verify(chain).doFilter(any(RequestBodyWrapper.class));
  }
}
