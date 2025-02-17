package com.retailsvc.http.openapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.BodyHandler.RequestBodyWrapper;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetRequestBodyTest {

  private HttpExchange exchange;

  private final byte[] body = "hello".getBytes();

  @BeforeEach
  void setUp() {
    exchange = mock();
  }

  @Test
  void thatBodyBytesReturnedWhenInstanceOfRequestBodyWrapper() throws Exception {
    RequestBodyWrapper wrapper = new RequestBodyWrapper(exchange, body);
    GetRequestBody impl = new GetRequestBody() {};

    byte[] requestBody = impl.getRequestBody(wrapper);

    assertThat(requestBody).isSameAs(body);
  }

  @Test
  void thatBodyBytesReturnedWhenNotInstanceOfRequestBodyWrapper() throws Exception {
    when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(body));

    GetRequestBody impl = new GetRequestBody() {};
    byte[] requestBody = impl.getRequestBody(exchange);

    assertThat(requestBody).isEqualTo(body);
  }
}
