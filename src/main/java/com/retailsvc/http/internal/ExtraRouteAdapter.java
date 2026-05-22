package com.retailsvc.http.internal;

import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.HttpMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Bridges an extra-route {@link RequestHandler} to the underlying JDK {@link HttpHandler}.
 *
 * <p>Builds a {@link Request} with {@code operationId=null}, empty path-params, empty principals,
 * raw body bytes, raw query, and the parsed HTTP method, then renders the returned {@link Response}
 * through the shared {@link ResponseRenderer}. OpenAPI validation, body parsing, and security are
 * intentionally bypassed — extras are by definition outside the spec.
 */
public final class ExtraRouteAdapter implements HttpHandler {

  private final String path;
  private final RequestHandler handler;
  private final ResponseRenderer renderer;

  public ExtraRouteAdapter(String path, RequestHandler handler, ResponseRenderer renderer) {
    this.path = path;
    this.handler = handler;
    this.renderer = renderer;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String requested = exchange.getRequestURI().getPath();
    if (!path.equals(requested)) {
      throw new NotFoundException(exchange.getRequestMethod() + " " + requested);
    }
    byte[] body = exchange.getRequestBody().readAllBytes();
    HttpMethod method = HttpMethod.parse(exchange.getRequestMethod());
    var headers = exchange.getRequestHeaders();
    Request request =
        new Request(
            body,
            null,
            null,
            null,
            Map.of(),
            exchange.getRequestURI().getRawQuery(),
            headers::getFirst,
            Map.of(),
            method);
    Response response = handler.handle(request);
    renderer.render(exchange, response);
  }
}
