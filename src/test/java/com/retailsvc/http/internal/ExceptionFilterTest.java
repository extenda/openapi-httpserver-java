package com.retailsvc.http.internal;

import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.NotFoundException;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ExceptionFilterTest {
  @Test
  void delegatesToExceptionHandler() throws Exception {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    ExceptionHandler handler = Mockito.mock(ExceptionHandler.class);
    Filter f = new ExceptionFilter(handler);
    Filter.Chain chain = Mockito.mock(Filter.Chain.class);
    Mockito.doThrow(new NotFoundException("x")).when(chain).doFilter(ex);
    f.doFilter(ex, chain);
    Mockito.verify(handler).handle(Mockito.eq(ex), Mockito.any(NotFoundException.class));
  }

  @Test
  void passThroughOnSuccess() throws Exception {
    HttpExchange ex = Mockito.mock(HttpExchange.class);
    ExceptionHandler handler = Mockito.mock(ExceptionHandler.class);
    Filter f = new ExceptionFilter(handler);
    Filter.Chain chain = Mockito.mock(Filter.Chain.class);
    f.doFilter(ex, chain);
    Mockito.verify(chain).doFilter(ex);
    Mockito.verifyNoInteractions(handler);
  }
}
