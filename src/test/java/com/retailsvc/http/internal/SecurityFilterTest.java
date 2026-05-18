package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
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

  @Test
  void missingCredentialReturns401WithBearerChallenge() throws Exception {
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

    SecurityFilter filter =
        new SecurityFilter(
            Map.of("getX", op),
            Map.of("bearerAuth", new HttpBearer(Optional.empty())),
            List.of(),
            Map.of("bearerAuth", (req, cred) -> Optional.of("never-called")),
            false);

    HttpExchange ex = mock(HttpExchange.class);
    Headers headers = new Headers();
    when(ex.getRequestHeaders()).thenReturn(headers);
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    when(ex.getResponseBody()).thenReturn(body);
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/getX"));

    Chain chain = mock(Chain.class);
    ScopedValueHarness.runWith(newMinimalRequest("getX"), () -> filter.doFilter(ex, chain));

    verify(ex).sendResponseHeaders(eq(401), anyLong());
    assertThat(responseHeaders.getFirst("WWW-Authenticate")).isEqualTo("Bearer realm=\"api\"");
    assertThat(responseHeaders.getFirst("Content-Type")).isEqualTo("application/problem+json");
    assertThat(body.toString())
        .contains("\"status\":401")
        .contains("credential missing")
        .contains("bearerAuth");
  }

  @Test
  void deniedValidatorReturns403WithoutChallenge() throws Exception {
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

    SecurityFilter filter =
        new SecurityFilter(
            Map.of("getX", op),
            Map.of("bearerAuth", new HttpBearer(Optional.empty())),
            List.of(),
            Map.of("bearerAuth", (req, cred) -> Optional.empty()),
            false);

    HttpExchange ex = mock(HttpExchange.class);
    Headers headers = new Headers();
    headers.add("Authorization", "Bearer t");
    when(ex.getRequestHeaders()).thenReturn(headers);
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/getX"));

    Chain chain = mock(Chain.class);
    ScopedValueHarness.runWith(newMinimalRequest("getX"), () -> filter.doFilter(ex, chain));

    verify(ex).sendResponseHeaders(eq(403), anyLong());
    assertThat(responseHeaders.getFirst("WWW-Authenticate")).isNull();
  }

  @Test
  void apiKeyMissingReturnsApiKeyChallengeHeader() throws Exception {
    Operation op =
        new Operation(
            "getX",
            HttpMethod.GET,
            null,
            Optional.empty(),
            List.of(),
            Map.of(),
            Map.of(),
            Optional.of(List.of(new SecurityRequirement(Map.of("apiKeyAuth", List.of())))));

    SecurityFilter filter =
        new SecurityFilter(
            Map.of("getX", op),
            Map.of("apiKeyAuth", new ApiKey("X-API-Key", Location.HEADER)),
            List.of(),
            Map.of("apiKeyAuth", (req, cred) -> Optional.of("ok")),
            false);

    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestHeaders()).thenReturn(new Headers());
    Headers responseHeaders = new Headers();
    when(ex.getResponseHeaders()).thenReturn(responseHeaders);
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    when(ex.getRequestURI()).thenReturn(URI.create("http://h/getX"));

    ScopedValueHarness.runWith(
        newMinimalRequest("getX"), () -> filter.doFilter(ex, mock(Chain.class)));

    verify(ex).sendResponseHeaders(eq(401), anyLong());
    assertThat(responseHeaders.getFirst("WWW-Authenticate"))
        .isEqualTo("ApiKey location=header, name=\"X-API-Key\"");
  }

  private static Request newMinimalRequest(String operationId) {
    return new Request(new byte[0], null, null, operationId, Map.of(), null, h -> null);
  }
}
