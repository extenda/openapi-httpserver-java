package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.retailsvc.http.openapi.exceptions.BadRequestException;
import com.retailsvc.http.openapi.exceptions.OperationIdNotFoundException;
import com.sun.net.httpserver.Filter.Chain;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExceptionHandlingFilterTest {

  @Mock ExceptionHandler mockExceptionHandler;
  @Mock HttpExchange mockExchange;
  @Mock Chain mockChain;

  ExceptionHandlingFilter exceptionHandlingFilter;

  @BeforeEach
  void setUp() {
    exceptionHandlingFilter = new ExceptionHandlingFilter(mockExceptionHandler);
  }

  @Test
  void testDoFilterNoException() throws IOException {
    exceptionHandlingFilter.doFilter(mockExchange, mockChain);
    verify(mockChain, times(1)).doFilter(mockExchange);
  }

  @Test
  void testDoFilterWithBadRequestException() throws IOException {
    BadRequestException badRequestException = new BadRequestException();
    doThrow(badRequestException).when(mockChain).doFilter(mockExchange);

    exceptionHandlingFilter.doFilter(mockExchange, mockChain);

    ArgumentCaptor<BadRequestException> exceptionCaptor =
        ArgumentCaptor.forClass(BadRequestException.class);
    verify(mockExceptionHandler).handleException(eq(mockExchange), exceptionCaptor.capture());
    assertThat(exceptionCaptor.getValue()).isSameAs(badRequestException);
  }

  @Test
  void testDoFilterWithNotFoundException() throws IOException {
    doThrow(new OperationIdNotFoundException("GET", "/")).when(mockChain).doFilter(mockExchange);

    exceptionHandlingFilter.doFilter(mockExchange, mockChain);

    verify(mockExchange).sendResponseHeaders(404, 0);
    verify(mockExceptionHandler, never()).handleException(any(), any());
  }

  @Test
  void testDoFilterWithOtherException() throws IOException {
    RuntimeException otherException = new RuntimeException("Other exception");
    doThrow(otherException).when(mockChain).doFilter(mockExchange);

    exceptionHandlingFilter.doFilter(mockExchange, mockChain);

    ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
    verify(mockExceptionHandler).handleException(eq(mockExchange), exceptionCaptor.capture());
    assertThat(exceptionCaptor.getValue()).isSameAs(otherException);
  }

  @Test
  void testDescription() {
    assertThat(exceptionHandlingFilter.description()).isEqualTo("Exception handling filter");
  }
}
