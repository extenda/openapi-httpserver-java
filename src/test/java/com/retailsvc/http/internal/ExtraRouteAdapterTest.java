package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.GsonTypeMapper;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.TypeMapper;
import com.retailsvc.http.spec.HttpMethod;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtraRouteAdapterTest {

  @Test
  void buildsRequestWithMethodQueryHeadersAndBodyBytesAndNullOperationId() throws Exception {
    AtomicReference<Request> captured = new AtomicReference<>();
    RequestHandler handler =
        req -> {
          captured.set(req);
          return Response.empty();
        };

    Map<String, TypeMapper> mappers = Map.of("application/json", new GsonTypeMapper());
    ResponseRenderer renderer = new ResponseRenderer(mappers);
    ExtraRouteAdapter adapter = new ExtraRouteAdapter(handler, renderer);

    HttpExchange ex = mock(HttpExchange.class);
    Headers reqHeaders = new Headers();
    reqHeaders.add("X-Trace", "abc");
    when(ex.getRequestMethod()).thenReturn("POST");
    when(ex.getRequestURI()).thenReturn(new URI("/alive?x=1"));
    when(ex.getRequestHeaders()).thenReturn(reqHeaders);
    when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream("hi".getBytes()));
    when(ex.getResponseHeaders()).thenReturn(new Headers());
    when(ex.getResponseBody()).thenReturn(new ByteArrayOutputStream());

    adapter.handle(ex);

    Request r = captured.get();
    assertThat(r.operationId()).isNull();
    assertThat(r.pathParams()).isEmpty();
    assertThat(r.principals()).isEmpty();
    assertThat(r.method()).isEqualTo(HttpMethod.POST);
    assertThat(r.rawQuery()).isEqualTo("x=1");
    assertThat(r.header("X-Trace")).contains("abc");
    assertThat(r.bytes()).containsExactly('h', 'i');
  }
}
