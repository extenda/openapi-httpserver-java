package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DecoratorAndInterceptorIT extends ServerBaseTest {

  static final ScopedValue<String> TENANT = ScopedValue.newInstance();

  @Test
  void responseDecoratorAddsHeadersOnEveryResponse() throws Exception {
    RequestHandler ok = req -> Response.text(HTTP_OK, "ok");
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", ok, "post-data", ok)))
            .responseDecorator((req, resp) -> resp.withHeader("X-Correlation-Id", "decorator-cid"))
            .responseDecorator((req, resp) -> resp.withHeader("X-Op", req.operationId()))
            .port(0)
            .build();

    var resp = call("/api/v1/data");

    assertThat(resp.statusCode()).isEqualTo(HTTP_OK);
    assertThat(resp.headers().firstValue("X-Correlation-Id")).contains("decorator-cid");
    assertThat(resp.headers().firstValue("X-Op")).contains("get-data");
  }

  @Test
  void decoratorHeaderOverridesHandlerHeader() throws Exception {
    RequestHandler ok = req -> Response.text(HTTP_OK, "ok").withHeader("X-Op", "handler-set");
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", ok, "post-data", ok)))
            .responseDecorator((req, resp) -> resp.withHeader("X-Op", "decorator-wins"))
            .port(0)
            .build();

    var resp = call("/api/v1/data");

    assertThat(resp.headers().firstValue("X-Op")).contains("decorator-wins");
  }

  @Test
  void interceptorBindsScopedValueVisibleToHandler() throws Exception {
    RequestHandler echoTenant = req -> Response.text(HTTP_OK, TENANT.get());
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", echoTenant, "post-data", echoTenant)))
            .interceptor((request, next) -> ScopedValue.where(TENANT, "acme").call(next::proceed))
            .port(0)
            .build();

    assertThat(call("/api/v1/data").body()).isEqualTo("acme");
  }

  @Test
  void interceptorsRunInRegistrationOrder() throws Exception {
    List<String> trace = new CopyOnWriteArrayList<>();
    RequestHandler ok =
        req -> {
          trace.add("handler");
          return Response.status(HTTP_OK);
        };
    server =
        newBuilder()
            .spec(spec)
            .handlers(stubAllHandlers(Map.of("get-data", ok, "post-data", ok)))
            .interceptor(
                (request, next) -> {
                  trace.add("outer-before");
                  Response r = next.proceed();
                  trace.add("outer-after");
                  return r;
                })
            .interceptor(
                (request, next) -> {
                  trace.add("inner-before");
                  Response r = next.proceed();
                  trace.add("inner-after");
                  return r;
                })
            .port(0)
            .build();

    call("/api/v1/data");

    assertThat(trace)
        .containsExactly("outer-before", "inner-before", "handler", "inner-after", "outer-after");
  }

  private HttpResponse<String> call(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d%s".formatted(server.listenPort(), path)))
                .GET()
                .build(),
            ofString());
  }
}
