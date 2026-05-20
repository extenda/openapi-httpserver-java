package com.retailsvc.http;

import static com.retailsvc.http.spec.HttpMethod.GET;
import static com.retailsvc.http.spec.HttpMethod.HEAD;
import static com.retailsvc.http.spec.HttpMethod.POST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HandlersTest {

  private static final UnaryOperator<String> NO_HEADERS = name -> null;

  private static Request request(HttpMethod method) {
    return new Request(new byte[0], null, null, null, Map.of(), null, NO_HEADERS, Map.of(), method);
  }

  @Test
  void aliveHandlerReturns204OnGet() {
    Response resp = Handlers.aliveHandler().handle(request(GET));

    assertThat(resp.status()).isEqualTo(204);
    assertThat(resp.body()).isNull();
  }

  @Test
  void aliveHandlerReturns204OnHead() {
    Response resp = Handlers.aliveHandler().handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(204);
  }

  @Test
  void aliveHandlerReturns405OnPost() {
    Response resp = Handlers.aliveHandler().handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void specHandlerServesYamlBytesWithInferredContentType() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(GET));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.contentType()).isEqualTo("application/yaml");
    assertThat(resp.body()).isInstanceOf(byte[].class);
    assertThat((byte[]) resp.body()).isNotEmpty();
  }

  @Test
  void specHandlerInfersJsonContentType() {
    Response resp = Handlers.specHandler("/openapi.json").handle(request(GET));

    assertThat(resp.contentType()).isEqualTo("application/json");
  }

  @Test
  void specHandlerThrowsAtConstructionForMissingResource() {
    assertThatThrownBy(() -> Handlers.specHandler("/does-not-exist.yaml"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/does-not-exist.yaml");
  }

  @Test
  void specHandlerReturns405OnPost() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(POST));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsEntry("Allow", "GET, HEAD");
  }

  @Test
  void specHandlerHeadReturnsContentLengthWithoutBody() {
    Response resp = Handlers.specHandler("/openapi.yaml").handle(request(HEAD));

    assertThat(resp.status()).isEqualTo(200);
    assertThat(resp.body()).isNull();
    assertThat(resp.headers()).containsKey("Content-Length");
    assertThat(Integer.parseInt(resp.headers().get("Content-Length"))).isGreaterThan(0);
  }
}
