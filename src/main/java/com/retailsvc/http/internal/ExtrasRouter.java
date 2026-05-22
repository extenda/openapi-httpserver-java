package com.retailsvc.http.internal;

import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.HttpMethod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dispatches extra-route requests using exact and wildcard path matching. */
public final class ExtrasRouter implements HttpHandler {

  private record Entry(PathPattern pattern, RequestHandler handler) {}

  private final Map<String, RequestHandler> exact;
  private final List<Entry> wildcards;
  private final ResponseRenderer renderer;

  public ExtrasRouter(Map<String, RequestHandler> extras, ResponseRenderer renderer) {
    this.renderer = renderer;
    Map<String, RequestHandler> exactBuilder = new LinkedHashMap<>();
    List<Entry> wildcardBuilder = new ArrayList<>();
    for (Map.Entry<String, RequestHandler> e : extras.entrySet()) {
      PathPattern p = PathPattern.compile(e.getKey());
      if (p.hasWildcard()) {
        wildcardBuilder.add(new Entry(p, e.getValue()));
      } else {
        exactBuilder.put(p.raw(), e.getValue());
      }
    }
    this.exact = Map.copyOf(exactBuilder);
    this.wildcards = List.copyOf(wildcardBuilder);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String decoded = ExtrasPathValidator.validateAndDecode(exchange.getRequestURI());

    RequestHandler hit = exact.get(decoded);
    if (hit == null) {
      for (Entry e : wildcards) {
        if (e.pattern().matches(decoded)) {
          hit = e.handler();
          break;
        }
      }
    }
    if (hit == null) {
      throw new NotFoundException(exchange.getRequestMethod() + " " + decoded);
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
    Response response = hit.handle(request);
    renderer.render(exchange, response);
  }
}
