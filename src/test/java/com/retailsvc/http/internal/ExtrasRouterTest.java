package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.GsonTypeMapper;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtrasRouterTest {

  @Test
  void exactMatchDispatches() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put(
        "/alive",
        req -> {
          hit.set("alive");
          return Response.empty();
        });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/alive");

    assertThat(hit.get()).isEqualTo("alive");
  }

  @Test
  void exactMatchRequiresExactPath() {
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/alive", req -> Response.empty());
    ExtrasRouter router = newRouter(extras);

    assertThatThrownBy(() -> invoke(router, "/alive232")).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> invoke(router, "/alive/34")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void singleStarMatchesOneSegment() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put(
        "/static/*",
        req -> {
          hit.set("static");
          return Response.empty();
        });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/static/style.css");
    assertThat(hit.get()).isEqualTo("static");
  }

  @Test
  void doubleStarMatchesAnyDepth() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put(
        "/files/**",
        req -> {
          hit.set("files");
          return Response.empty();
        });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/files/a/b/c");
    assertThat(hit.get()).isEqualTo("files");
  }

  @Test
  void exactWinsOverWildcard() throws Exception {
    AtomicReference<String> hit = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put(
        "/files/**",
        req -> {
          hit.set("wild");
          return Response.empty();
        });
    extras.put(
        "/files/special",
        req -> {
          hit.set("exact");
          return Response.empty();
        });
    ExtrasRouter router = newRouter(extras);

    invoke(router, "/files/special");
    assertThat(hit.get()).isEqualTo("exact");
  }

  @Test
  void noMatchThrowsNotFound() {
    ExtrasRouter router = newRouter(Map.of());
    assertThatThrownBy(() -> invoke(router, "/nope")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void traversalRejected() {
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put("/files/**", req -> Response.empty());
    ExtrasRouter router = newRouter(extras);

    assertThatThrownBy(() -> invoke(router, "/files/../etc/passwd"))
        .isInstanceOf(BadRequestException.class);
  }

  private static ExtrasRouter newRouter(Map<String, RequestHandler> extras) {
    Map<String, TypeMapper> mappers = Map.of("application/json", new GsonTypeMapper());
    return new ExtrasRouter(extras, new ResponseRenderer(mappers));
  }

  private static void invoke(ExtrasRouter router, String path) throws Exception {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn("GET");
    when(ex.getRequestURI()).thenReturn(URI.create(path));
    when(ex.getRequestHeaders()).thenReturn(new Headers());
    when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    router.handle(ex);
  }
}
