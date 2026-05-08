package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpExceptionsTest {
  @Test
  void notFoundCarriesPath() {
    NotFoundException e = new NotFoundException("GET /missing");
    assertThat(e.getMessage()).isEqualTo("GET /missing");
  }

  @Test
  void methodNotAllowedCarriesAllowedSet() {
    MethodNotAllowedException e =
        new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST));
    assertThat(e.allowed()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
  }
}
