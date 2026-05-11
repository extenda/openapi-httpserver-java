package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.PathTemplate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouterTest {
  private Operation op(String id, HttpMethod m, String path) {
    return new Operation(
        id, m, PathTemplate.compile(path), Optional.empty(), List.of(), Map.of(), Map.of());
  }

  @Test
  void exactPathMatchByMethod() {
    Router r =
        new Router(List.of(op("a", HttpMethod.GET, "/users"), op("b", HttpMethod.POST, "/users")));
    assertThat(r.match(HttpMethod.GET, "/users").orElseThrow().operation().operationId())
        .isEqualTo("a");
    assertThat(r.match(HttpMethod.POST, "/users").orElseThrow().operation().operationId())
        .isEqualTo("b");
  }

  @Test
  void templatedPathExtractsParam() {
    Router r = new Router(List.of(op("g", HttpMethod.GET, "/users/{id}")));
    Router.Match m = r.match(HttpMethod.GET, "/users/42").orElseThrow();
    assertThat(m.operation().operationId()).isEqualTo("g");
    assertThat(m.pathParameters()).containsEntry("id", "42");
  }

  @Test
  void unknownPathReturnsEmpty() {
    Router r = new Router(List.of(op("g", HttpMethod.GET, "/users")));
    assertThat(r.match(HttpMethod.GET, "/orders")).isEmpty();
  }

  @Test
  void allowedMethodsForKnownPath() {
    Router r =
        new Router(List.of(op("a", HttpMethod.GET, "/users"), op("b", HttpMethod.POST, "/users")));
    assertThat(r.allowedMethods("/users"))
        .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    assertThat(r.allowedMethods("/missing")).isEmpty();
  }
}
