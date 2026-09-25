package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResponseTest {

  private static final int HTTP_UNPROCESSABLE_CONTENT = 422;

  @Test
  void acceptedNoBody() {
    Response r = Response.accepted();

    assertThat(r.status()).isEqualTo(HTTP_ACCEPTED);
    assertThat(r.body()).isNull();
    assertThat(r.headers()).isEmpty();
  }

  @Test
  void acceptedWithBody() {
    Map<String, String> job = Map.of("id", "job-42");
    Response r = Response.accepted(job);

    assertThat(r.status()).isEqualTo(HTTP_ACCEPTED);
    assertThat(r.body()).isEqualTo(job);
  }

  @Test
  void createdWithBody() {
    Map<String, String> resource = Map.of("id", "x-1");
    Response r = Response.created(resource);

    assertThat(r.status()).isEqualTo(HTTP_CREATED);
    assertThat(r.body()).isEqualTo(resource);
    assertThat(r.headers()).isEmpty();
  }

  @Test
  void createdWithLocationViaWithHeader() {
    Response r = Response.created(Map.of("id", "x-1")).withHeader("Location", "/things/x-1");

    assertThat(r.status()).isEqualTo(HTTP_CREATED);
    assertThat(r.headers()).containsEntry("Location", "/things/x-1");
  }

  @Test
  void createdWithLocationViaWithLocation() {
    Response r = Response.created(Map.of("id", "x-1")).withLocation("/things/x-1");

    assertThat(r.status()).isEqualTo(HTTP_CREATED);
    assertThat(r.headers()).containsEntry("Location", "/things/x-1");
  }

  @Test
  void notFoundNoBody() {
    Response r = Response.notFound();

    assertThat(r.status()).isEqualTo(HTTP_NOT_FOUND);
    assertThat(r.body()).isNull();
  }

  @Test
  void notFoundWithBody() {
    Map<String, String> problem = Map.of("title", "Missing");
    Response r = Response.notFound(problem);

    assertThat(r.status()).isEqualTo(HTTP_NOT_FOUND);
    assertThat(r.body()).isEqualTo(problem);
  }

  @Test
  void notImplementedNoBody() {
    Response r = Response.notImplemented();

    assertThat(r.status()).isEqualTo(HTTP_NOT_IMPLEMENTED);
    assertThat(r.body()).isNull();
  }

  static Stream<Arguments> noBodyFactories() {
    return Stream.of(
        Arguments.of((Supplier<Response>) Response::ok, HTTP_OK),
        Arguments.of((Supplier<Response>) Response::noContent, HTTP_NO_CONTENT),
        Arguments.of((Supplier<Response>) Response::empty, HTTP_NO_CONTENT),
        Arguments.of((Supplier<Response>) Response::notModified, HTTP_NOT_MODIFIED),
        Arguments.of((Supplier<Response>) Response::badRequest, HTTP_BAD_REQUEST),
        Arguments.of((Supplier<Response>) Response::unauthorized, HTTP_UNAUTHORIZED),
        Arguments.of((Supplier<Response>) Response::forbidden, HTTP_FORBIDDEN),
        Arguments.of((Supplier<Response>) Response::conflict, HTTP_CONFLICT),
        Arguments.of((Supplier<Response>) Response::internalServerError, HTTP_INTERNAL_ERROR));
  }

  @ParameterizedTest
  @MethodSource("noBodyFactories")
  void noBodyFactoryHasStatusAndNoBody(Supplier<Response> factory, int status) {
    Response r = factory.get();

    assertThat(r.status()).isEqualTo(status);
    assertThat(r.body()).isNull();
    assertThat(r.contentType()).isNull();
    assertThat(r.headers()).isEmpty();
  }

  static Stream<Arguments> bodyFactories() {
    return Stream.of(
        Arguments.of((Function<Object, Response>) Response::badRequest, HTTP_BAD_REQUEST),
        Arguments.of((Function<Object, Response>) Response::forbidden, HTTP_FORBIDDEN),
        Arguments.of((Function<Object, Response>) Response::conflict, HTTP_CONFLICT),
        Arguments.of(
            (Function<Object, Response>) Response::unprocessableContent,
            HTTP_UNPROCESSABLE_CONTENT));
  }

  @ParameterizedTest
  @MethodSource("bodyFactories")
  void bodyFactoryHasStatusAndBody(Function<Object, Response> factory, int status) {
    Map<String, String> problem = Map.of("title", "Nope");
    Response r = factory.apply(problem);

    assertThat(r.status()).isEqualTo(status);
    assertThat(r.body()).isEqualTo(problem);
    assertThat(r.headers()).isEmpty();
  }

  @Test
  void methodNotAllowedListsMethodsInAllowHeader() {
    Response r = Response.methodNotAllowed(GET, HEAD);

    assertThat(r.status()).isEqualTo(HTTP_BAD_METHOD);
    assertThat(r.body()).isNull();
    assertThat(r.headers()).containsExactly(Map.entry("Allow", "GET, HEAD"));
  }

  @Test
  void methodNotAllowedOrdersAndDeduplicatesMethods() {
    Response r = Response.methodNotAllowed(HEAD, POST, GET, HEAD);

    assertThat(r.headers()).containsEntry("Allow", "GET, POST, HEAD");
  }

  @Test
  void methodNotAllowedAcceptsCollection() {
    Response r = Response.methodNotAllowed(Set.of(POST, GET));

    assertThat(r.headers()).containsEntry("Allow", "GET, POST");
  }
}
