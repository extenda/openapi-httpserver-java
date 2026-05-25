package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.DELETE;
import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.OPTIONS;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static com.retailsvc.http.spec.HttpMethod.PUT;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.HttpMethod;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
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

  @Test
  void corsPreflightHandlerRejectsNonOptionsWith405AndAllowOptions() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp = handler.handle(bare(GET));

    assertThat(resp.status()).isEqualTo(HTTP_BAD_METHOD);
    assertThat(resp.headers()).containsEntry("Allow", "OPTIONS");
  }

  @Test
  void corsPreflightHandlerRejectsMissingOriginWith400() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    assertThatThrownBy(() -> handler.handle(preflight(null, "POST", "content-type")))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Origin");
  }

  @Test
  void corsPreflightHandlerRejectsMissingRequestMethodWith400() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    assertThatThrownBy(
            () -> handler.handle(preflight("https://app.example.com", null, "content-type")))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Access-Control-Request-Method");
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedOriginWith403() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp = handler.handle(preflight("https://evil.example.com", "POST", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
    assertThat(resp.headers()).doesNotContainKey("Access-Control-Allow-Origin");
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedMethodWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, List.of(GET), HEADERS, false, null);

    Response resp = handler.handle(preflight("https://app.example.com", "DELETE", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerRejectsDisallowedHeaderWith403() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(ORIGINS, METHODS, List.of("content-type"), false, null);

    Response resp = handler.handle(preflight("https://app.example.com", "POST", "x-secret"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerRejectsUnknownMethodTokenWith403() {
    RequestHandler handler = Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, null);

    Response resp = handler.handle(preflight("https://app.example.com", "BOGUS", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerMatchesHeadersCaseInsensitively() {
    RequestHandler handler =
        Handlers.corsPreflightHandler(
            ORIGINS, METHODS, List.of("Content-Type", "Authorization"), false, null);

    Response resp =
        handler.handle(preflight("https://app.example.com", "POST", "CONTENT-TYPE, authorization"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
  }

  @Test
  void corsPreflightHandlerEchoesOriginAndIncludesVary() {
    Predicate<String> anyExampleOrigin = o -> o.endsWith(".example.com");
    RequestHandler handler =
        Handlers.corsPreflightHandler(anyExampleOrigin, METHODS, HEADERS, false, null);

    Response resp =
        handler.handle(preflight("https://tenant-7.example.com", "POST", "content-type"));

    assertThat(resp.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(resp.headers())
        .containsEntry("Access-Control-Allow-Origin", "https://tenant-7.example.com")
        .containsEntry("Vary", "Origin");
  }

  @Test
  void corsPreflightHandlerListOverloadDelegatesToPredicateBehaviour() {
    RequestHandler list =
        Handlers.corsPreflightHandler(
            List.of("https://a.example.com", "https://b.example.com"),
            METHODS,
            HEADERS,
            false,
            null);

    Response allowed = list.handle(preflight("https://b.example.com", "POST", "content-type"));
    Response denied = list.handle(preflight("https://c.example.com", "POST", "content-type"));

    assertThat(allowed.status()).isEqualTo(HTTP_NO_CONTENT);
    assertThat(denied.status()).isEqualTo(HTTP_FORBIDDEN);
  }

  @Test
  void corsPreflightHandlerRejectsNullOriginList() {
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler((List<String>) null, METHODS, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedOrigins");
  }

  @Test
  void corsPreflightHandlerRejectsNullOriginPredicate() {
    assertThatThrownBy(
            () ->
                Handlers.corsPreflightHandler(
                    (Predicate<String>) null, METHODS, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("originAllowed");
  }

  @Test
  void corsPreflightHandlerRejectsNullMethods() {
    assertThatThrownBy(() -> Handlers.corsPreflightHandler(ORIGINS, null, HEADERS, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedMethods");
  }

  @Test
  void corsPreflightHandlerRejectsEmptyMethods() {
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, List.of(), HEADERS, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowedMethods");
  }

  @Test
  void corsPreflightHandlerRejectsNullHeaders() {
    assertThatThrownBy(() -> Handlers.corsPreflightHandler(ORIGINS, METHODS, null, false, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("allowedHeaders");
  }

  @Test
  void corsPreflightHandlerRejectsNegativeMaxAge() {
    assertThatThrownBy(
            () ->
                Handlers.corsPreflightHandler(
                    ORIGINS, METHODS, HEADERS, false, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAge");
  }

  @Test
  void corsPreflightHandlerRejectsOverflowingMaxAge() {
    Duration tooBig = Duration.ofSeconds((long) Integer.MAX_VALUE + 1);
    assertThatThrownBy(
            () -> Handlers.corsPreflightHandler(ORIGINS, METHODS, HEADERS, false, tooBig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAge");
  }
}
