package com.retailsvc.http.internal;

import static org.mockito.Mockito.mock;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Response;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ExceptionFilterTest {

  @Test
  void delegatesToExceptionHandler() throws Exception {
    HttpExchange ex = mock(HttpExchange.class);
    ResponseRenderer renderer = mock(ResponseRenderer.class);
    ExceptionHandler handler = mock(ExceptionHandler.class);
    Mockito.when(handler.handle(Mockito.any())).thenReturn(Response.status(404));
    Filter f = new ExceptionFilter(handler, renderer);
    Filter.Chain chain = mock(Filter.Chain.class);
    Mockito.doThrow(new NotFoundException("x")).when(chain).doFilter(ex);
    f.doFilter(ex, chain);
    Mockito.verify(handler).handle(Mockito.any(NotFoundException.class));
  }

  @Test
  void passThroughOnSuccess() throws Exception {
    HttpExchange ex = mock(HttpExchange.class);
    ResponseRenderer renderer = mock(ResponseRenderer.class);
    ExceptionHandler handler = mock(ExceptionHandler.class);
    Filter f = new ExceptionFilter(handler, renderer);
    Filter.Chain chain = mock(Filter.Chain.class);
    f.doFilter(ex, chain);
    Mockito.verify(chain).doFilter(ex);
    Mockito.verifyNoInteractions(handler);
  }
}
