package com.retailsvc.http.internal;

import static com.retailsvc.http.internal.ResponseRenderer.DEFAULT_MIN_COMPRESSIBLE_BYTES;
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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
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

  @Test
  void gzipRequestBodyIsInflatedForExtraRoutes() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    Map<String, RequestHandler> extras = new LinkedHashMap<>();
    extras.put(
        "/echo",
        req -> {
          seen.set(new String(req.bytes(), StandardCharsets.UTF_8));
          return Response.empty();
        });
    Headers headers = new Headers();
    headers.add("Content-Encoding", "gzip");

    invoke(newRouter(extras), "/echo", gzip("hello".getBytes(StandardCharsets.UTF_8)), headers);

    assertThat(seen.get()).isEqualTo("hello");
  }

  private static ExtrasRouter newRouter(Map<String, RequestHandler> extras) {
    Map<String, TypeMapper> mappers = Map.of("application/json", new GsonTypeMapper());
    return new ExtrasRouter(
        extras,
        new ResponseRenderer(
            mappers,
            DEFAULT_MIN_COMPRESSIBLE_BYTES,
            ContentCodings.of(List.of(), List.of()).encoders()),
        new RequestBodyReader(
            RequestBodyReader.DEFAULT_MAX_DECOMPRESSED_BYTES,
            ContentCodings.of(List.of(), List.of()).decoders()));
  }

  private static void invoke(ExtrasRouter router, String path) throws Exception {
    invoke(router, path, new byte[0], new Headers());
  }

  private static void invoke(ExtrasRouter router, String path, byte[] body, Headers headers)
      throws Exception {
    HttpExchange ex = mock(HttpExchange.class);
    when(ex.getRequestMethod()).thenReturn("GET");
    when(ex.getRequestURI()).thenReturn(URI.create(path));
    when(ex.getRequestHeaders()).thenReturn(headers);
    when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());
    router.handle(ex);
  }

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(data);
    }
    return out.toByteArray();
  }
}
