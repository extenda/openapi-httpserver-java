package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamingRequestHandlerTest {

  @Test
  void decodesByDefault() {
    StreamingRequestHandler handler = request -> Response.ok();

    assertThat(handler.decodeContent()).isTrue();
  }

  @Test
  void rawOptsOutOfDecodingAndDelegates() {
    Response expected = Response.noContent();
    StreamingRequestHandler raw = StreamingRequestHandler.raw(request -> expected);
    Request request = new Request(new byte[0], null, null, "op", Map.of(), null, name -> null);

    assertThat(raw.decodeContent()).isFalse();
    assertThat(raw.handle(request)).isSameAs(expected);
  }

  @Test
  void rawRejectsNull() {
    assertThatThrownBy(() -> StreamingRequestHandler.raw(null))
        .isInstanceOf(NullPointerException.class);
  }
}
