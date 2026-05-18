package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseTest {

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
}
