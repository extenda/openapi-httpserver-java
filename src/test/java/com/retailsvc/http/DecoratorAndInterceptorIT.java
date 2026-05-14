package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecoratorAndInterceptorIT extends ServerBaseTest {

  static final ScopedValue<String> TENANT = ScopedValue.newInstance();

  @Test
  void responseDecoratorAddsHeadersOnEveryResponse() throws Exception {
    RequestHandler ok = req -> req.respond(HTTP_OK).text("ok");
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("get-data", ok, "post-data", ok))
            .responseDecorator(
                (request, builder) -> builder.header("X-Correlation-Id", "decorator-cid"))
            .responseDecorator((request, builder) -> builder.header("X-Op", request.operationId()))
            .port(0)
            .build();

    var resp = call("/api/v1/data");

    assertThat(resp.statusCode()).isEqualTo(HTTP_OK);
    assertThat(resp.headers().firstValue("X-Correlation-Id")).contains("decorator-cid");
    assertThat(resp.headers().firstValue("X-Op")).contains("get-data");
  }

  @Test
  void handlerHeaderOverridesDecoratorHeader() throws Exception {
    RequestHandler ok = req -> req.respond(HTTP_OK).header("X-Op", "handler-wins").text("ok");
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("get-data", ok, "post-data", ok))
            .responseDecorator((request, builder) -> builder.header("X-Op", "decorator"))
            .port(0)
            .build();

    var resp = call("/api/v1/data");

    assertThat(resp.headers().firstValue("X-Op")).contains("handler-wins");
  }

  @Test
  void interceptorBindsScopedValueVisibleToHandler() throws Exception {
    RequestHandler echoTenant = req -> req.respond(HTTP_OK).text(TENANT.get());
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("get-data", echoTenant, "post-data", echoTenant))
            .interceptor(
                (request, next) ->
                    ScopedValue.where(TENANT, "acme")
                        .call(
                            () -> {
                              next.proceed();
                              return null;
                            }))
            .port(0)
            .build();

    assertThat(call("/api/v1/data").body()).isEqualTo("acme");
  }

  @Test
  void interceptorsRunInRegistrationOrder() throws Exception {
    List<String> trace = new java.util.concurrent.CopyOnWriteArrayList<>();
    RequestHandler ok =
        req -> {
          trace.add("handler");
          req.respond(HTTP_OK).empty();
        };
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(Map.of("get-data", ok, "post-data", ok))
            .interceptor(
                (request, next) -> {
                  trace.add("outer-before");
                  next.proceed();
                  trace.add("outer-after");
                })
            .interceptor(
                (request, next) -> {
                  trace.add("inner-before");
                  next.proceed();
                  trace.add("inner-after");
                })
            .port(0)
            .build();

    call("/api/v1/data");

    assertThat(trace)
        .containsExactly("outer-before", "inner-before", "handler", "inner-after", "outer-after");
  }

  private java.net.http.HttpResponse<String> call(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(server.listenPort(), path)))
                .GET()
                .build(),
            ofString());
  }
}
