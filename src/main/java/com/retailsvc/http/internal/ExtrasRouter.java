package com.retailsvc.http.internal;

import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.StreamingRequestHandler;
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
  private final RequestBodyReader bodyReader;
  private final StreamingLimit streamingLimit;

  public ExtrasRouter(
      Map<String, RequestHandler> extras, ResponseRenderer renderer, RequestBodyReader bodyReader) {
    this(extras, renderer, bodyReader, StreamingLimit.UNLIMITED);
  }

  /** As the 3-argument constructor, with {@code streamingLimit} capping streaming extras. */
  public ExtrasRouter(
      Map<String, RequestHandler> extras,
      ResponseRenderer renderer,
      RequestBodyReader bodyReader,
      StreamingLimit streamingLimit) {
    this.renderer = renderer;
    this.bodyReader = bodyReader;
    this.streamingLimit = streamingLimit;
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

    if (hit instanceof StreamingRequestHandler streaming) {
      handleStreaming(exchange, streaming);
      return;
    }
    Response response = hit.handle(bufferedRequest(exchange));
    renderer.render(exchange, response);
  }

  private void handleStreaming(HttpExchange exchange, StreamingRequestHandler handler)
      throws IOException {
    if (!streamingLimit.tryAcquire()) {
      renderer.render(exchange, streamingLimit.rejection());
      return;
    }
    try {
      Response response = handler.handle(streamingRequest(exchange, handler.decodeContent()));
      renderer.render(exchange, response);
    } finally {
      streamingLimit.release();
    }
  }

  private Request bufferedRequest(HttpExchange exchange) throws IOException {
    RequestBodyReader.Body body = bodyReader.read(exchange);
    return new Request(
            body.bytes(),
            null,
            null,
            null,
            Map.of(),
            exchange.getRequestURI().getRawQuery(),
            body.headerLookup(),
            Map.of(),
            HttpMethod.parse(exchange.getRequestMethod()))
        .withHeaders(body.headers());
  }

  private Request streamingRequest(HttpExchange exchange, boolean decode) {
    RequestBodyReader.Streamed body = bodyReader.stream(exchange, decode);
    return Request.streaming(
            body.stream(),
            null,
            Map.of(),
            exchange.getRequestURI().getRawQuery(),
            body.headerLookup(),
            Map.of(),
            HttpMethod.parse(exchange.getRequestMethod()))
        .withHeaders(body.headers());
  }
}
