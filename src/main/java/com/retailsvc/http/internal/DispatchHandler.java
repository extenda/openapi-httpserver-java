package com.retailsvc.http.internal;

import com.retailsvc.http.MissingOperationHandlerException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.RequestInterceptor;
import com.retailsvc.http.Response;
import com.retailsvc.http.ResponseDecorator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Final {@link HttpHandler} that dispatches a fully-prepared {@link Request} (bound via {@link
 * #CURRENT}) to the user-supplied {@link RequestHandler} for its {@code operationId}, wraps the
 * chain in the registered {@link RequestInterceptor}s, applies the registered {@link
 * ResponseDecorator}s to the result, then renders the final {@link Response} via the shared {@link
 * ResponseRenderer}.
 *
 * <p>If no handler is registered for the resolved {@code operationId}, this dispatcher throws
 * {@link MissingOperationHandlerException}.
 */
public final class DispatchHandler implements HttpHandler {

  /**
   * Thread-confined {@link ScopedValue} that binds the current {@link Request} for the lifetime of
   * dispatch.
   *
   * <p>Read by request interceptors and after-response hooks that need access to the request
   * without it being threaded through their signatures. The binding is not propagated across
   * executor boundaries — code that hands work off to another thread must capture the value
   * explicitly before doing so.
   */
  public static final ScopedValue<Request> CURRENT = ScopedValue.newInstance();

  /**
   * {@link com.sun.net.httpserver.HttpExchange} attribute key under which the rendered {@link
   * Response} is stashed for downstream filters.
   *
   * <p>Consumed by the access-log filter and after-response hooks that need to inspect the response
   * produced by this dispatcher.
   */
  public static final String RESPONSE_ATTR = "com.retailsvc.http.response";

  private final Map<String, RequestHandler> handlers;
  private final List<RequestInterceptor> interceptors;
  private final List<ResponseDecorator> decorators;
  private final ResponseRenderer renderer;

  /**
   * Creates a new dispatcher.
   *
   * @param handlers map of {@code operationId} to {@link RequestHandler} (defensively copied)
   * @param interceptors registered {@link RequestInterceptor}s in registration order (defensively
   *     copied)
   * @param decorators registered {@link ResponseDecorator}s in registration order (defensively
   *     copied)
   * @param renderer the shared {@link ResponseRenderer} used to write responses to the exchange
   */
  public DispatchHandler(
      Map<String, RequestHandler> handlers,
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators,
      ResponseRenderer renderer) {
    this.handlers = Map.copyOf(handlers);
    this.interceptors = List.copyOf(interceptors);
    this.decorators = List.copyOf(decorators);
    this.renderer = renderer;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Request request = CURRENT.get();
    RequestHandler handler = handlers.get(request.operationId());
    if (handler == null) {
      throw new MissingOperationHandlerException(request.operationId());
    }
    Response response = invoke(0, request, handler);
    for (ResponseDecorator decorator : decorators) {
      response = decorator.decorate(request, response);
    }
    exchange.setAttribute(RESPONSE_ATTR, response);
    renderer.render(exchange, response);
  }

  private Response invoke(int idx, Request request, RequestHandler handler) {
    if (idx == interceptors.size()) {
      return handler.handle(request);
    }
    return interceptors.get(idx).around(request, () -> invoke(idx + 1, request, handler));
  }
}
