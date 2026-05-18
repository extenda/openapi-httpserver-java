package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TypeMapperShapeTest {

  @Test
  void roundTripsViaInlineImplementation() {
    TypeMapper identity =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] body, String contentTypeHeader) {
            return new String(body, StandardCharsets.UTF_8);
          }

          @Override
          public byte[] writeTo(Object value) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
          }
        };

    Object read = identity.readFrom("hi".getBytes(StandardCharsets.UTF_8), "text/plain");
    assertThat(read).isEqualTo("hi");
    assertThat(identity.writeTo("hi")).containsExactly('h', 'i');
  }
}
