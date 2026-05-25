package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.DELETE;
import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static com.retailsvc.http.spec.HttpMethod.PUT;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class CorsPreflightHandlerTest {

  private static final List<HttpMethod> METHODS = List.of(GET, POST, PUT, DELETE);
  private static final List<String> HEADERS = List.of("content-type", "authorization");
  private static final List<String> ORIGINS = List.of("https://app.example.com");

  private static Request preflight(String origin, String requestMethod, String requestHeaders) {
    UnaryOperator<String> lookup =
        name ->
            switch (name.toLowerCase(Locale.ROOT)) {
              case "origin" -> origin;
              case "access-control-request-method" -> requestMethod;
              case "access-control-request-headers" -> requestHeaders;
              default -> null;
            };
    return new Request(new byte[0], null, null, null, Map.of(), null, lookup, Map.of(), OPTIONS);
  }

  private static Request bare(HttpMethod method) {
    return new Request(new byte[0], null, null, null, Map.of(), null, n -> null, Map.of(), method);
  }

  @Test
  void corsPreflightHandlerReturns204WithExpectedHeadersOnValidPreflight() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, true, Duration.ofMinutes(10));

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "content-type, authorization"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers())
        .containsEntry("Access-Control-Allow-Origin", "https://app.example.com")
        .containsEntry("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
        .containsEntry("Access-Control-Allow-Headers", "content-type, authorization")
        .containsEntry("Access-Control-Allow-Credentials", "true")
        .containsEntry("Access-Control-Max-Age", "600")
        .containsEntry("Vary", "Origin");
  }

  @Test
  void corsPreflightHandlerOmitsAllowCredentialsWhenFalse() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, Duration.ofMinutes(10));

    Response resp = handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Credentials");
  }

  @Test
  void corsPreflightHandlerOmitsMaxAgeWhenNull() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, true, null);

    Response resp = handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Max-Age");
  }

  @Test
  void corsPreflightHandlerEmitsMaxAgeInSecondsWhenSet() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, Duration.ofSeconds(75));

    Response resp = handler.handle(preflight("https://app.example.com", "POST", "content-type"));

    assertThat(resp.headers()).containsEntry("Access-Control-Max-Age", "75");
  }

  @Test
  void corsPreflightHandlerOmitsAllowHeadersWhenListEmpty() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, List.of(), false, null);

    Response resp = handler.handle(preflight("https://app.example.com", "POST", ""));

    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Headers");
    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
  }
}
