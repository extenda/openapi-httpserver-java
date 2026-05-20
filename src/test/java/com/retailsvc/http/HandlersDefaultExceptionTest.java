package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.validate.ValidationError;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HandlersDefaultExceptionTest {

  private static final TypeMapper JSON = new GsonTypeMapper();

  @Test
  void validationExceptionRendersProblemJson() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(
                new ValidationException(
                    new ValidationError("/x", "type", "expected string", null)));

    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    byte[] bytes = (byte[]) resp.body();
    String json = new String(bytes, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) JSON.readFrom(bytes, "application/json");
    assertThat(parsed).containsEntry("keyword", "type");
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(400);
    assertThat(json).contains("expected string");
  }

  @Test
  void badRequestExceptionRendersProblemJsonWithCustomStatus() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(new BadRequestException(422, "email taken", "/email", "unique"));

    assertThat(resp.status()).isEqualTo(422);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) JSON.readFrom((byte[]) resp.body(), "application/json");
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(422);
    assertThat(parsed)
        .containsEntry("title", "Unprocessable Content")
        .containsEntry("detail", "email taken")
        .containsEntry("pointer", "/email")
        .containsEntry("keyword", "unique");
  }

  @Test
  void notFoundReturns404() {
    Response resp = Handlers.defaultExceptionHandler(JSON).handle(new NotFoundException("GET /x"));

    assertThat(resp.status()).isEqualTo(404);
    assertThat(resp.body()).isNull();
  }

  @Test
  void methodNotAllowedReturns405WithAllowHeader() {
    Response resp =
        Handlers.defaultExceptionHandler(JSON)
            .handle(new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST)));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsKey("Allow");
    assertThat(resp.headers().get("Allow")).contains("GET").contains("POST");
  }

  @Test
  void unknownExceptionReturns500() {
    Response resp = Handlers.defaultExceptionHandler(JSON).handle(new RuntimeException("kaboom"));

    assertThat(resp.status()).isEqualTo(500);
    assertThat(resp.body()).isNull();
  }
}
