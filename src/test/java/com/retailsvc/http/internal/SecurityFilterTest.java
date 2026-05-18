package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.Request;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecurityFilterTest {

  @Test
  void allowsRequestWhenValidatorReturnsPrincipal() throws Exception {
    Operation op =
        new Operation(
            "getX",
            HttpMethod.GET,
            null,
            Optional.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            Optional.of(List.of(new SecurityRequirement(Map.of("bearerAuth", List.of())))));

    Map<String, SecurityScheme> schemes = Map.of("bearerAuth", new HttpBearer(Optional.empty()));
    Map<String, SchemeValidator> validators =
        Map.of("bearerAuth", (req, cred) -> Optional.of("user-1"));

    SecurityFilter filter =
        new SecurityFilter(Map.of("getX", op), schemes, List.of(), validators, false);

    HttpExchange ex = mock(HttpExchange.class);
    Headers headers = new Headers();
    headers.add("Authorization", "Bearer token-xyz");
    when(ex.getRequestHeaders()).thenReturn(headers);
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/getX"));

    AtomicReference<Map<String, Object>> capturedPrincipals = new AtomicReference<>();
    Chain chain = mock(Chain.class);
    doAnswer(
            inv -> {
              capturedPrincipals.set(DispatchHandler.CURRENT.get().principals());
              return null;
            })
        .when(chain)
        .doFilter(any());

    Request req = newMinimalRequest("getX");
    ScopedValueHarness.runWith(req, () -> filter.doFilter(ex, chain));

    verify(chain).doFilter(ex);
    assertThat(capturedPrincipals.get()).containsEntry("bearerAuth", "user-1");
  }

  @Test
  void passesThroughWhenOperationHasNoSecurity() throws Exception {
    Operation op =
        new Operation(
            "getY",
            HttpMethod.GET,
            null,
            Optional.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            Optional.empty()); // inherits root, root is empty

    SecurityFilter filter =
        new SecurityFilter(Map.of("getY", op), Map.of(), List.of(), Map.of(), false);

    HttpExchange ex = mock(HttpExchange.class);
    Chain chain = mock(Chain.class);
    ScopedValueHarness.runWith(newMinimalRequest("getY"), () -> filter.doFilter(ex, chain));

    verify(chain).doFilter(ex);
    assertThat(ScopedValueHarness.lastSeenPrincipals()).isEmpty();
  }

  private static Request newMinimalRequest(String operationId) {
    return new Request(new byte[0], null, null, operationId, Map.of(), null, h -> null);
  }
}
