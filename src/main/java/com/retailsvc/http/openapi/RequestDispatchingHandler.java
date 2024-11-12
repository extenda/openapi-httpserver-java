package com.retailsvc.http.openapi;

import static com.retailsvc.http.Handlers.internalServerErrorHandler;
import static java.util.Objects.requireNonNull;

import com.retailsvc.http.openapi.exceptions.OperationIdNotFoundException;
import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches a request to the correct handler.
 *
 * <p>Given the request url, it finds the corresponding operationId, and with it, the corresponding
 * {@link HttpHandler}, and calls it.
 *
 * @author thced
 */
public class RequestDispatchingHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(RequestDispatchingHandler.class);

  private final OpenApi specification;
  private final Map<String, HttpHandler> requestHandlers;

  /** Holds mapping between a method+path and an operationId */
  private final Map<String, String> operationIds;

  public RequestDispatchingHandler(OpenApi spec, Map<String, HttpHandler> requestHandlers) {
    LOG.debug("Instantiating RequestDispatchingHandler...");
    this.specification = requireNonNull(spec);
    this.requestHandlers = requireNonNull(requestHandlers);
    this.operationIds = new ConcurrentHashMap<>();
  }

  /**
   * Example flow:
   *
   * <ol>
   *   <li>'/api/v1/example'
   *   <li>convert to '/example'
   *   <li>under '/example' path, find 'example-operation-id'
   *   <li>using 'example-operation-id', find handler
   *   <li>run handler.handle(exchange)
   * </ol>
   *
   * @param exchange the exchange containing the request from the client and used to send the
   *     response
   * @throws IOException if I/O error occurs
   */
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    URI requestURI = exchange.getRequestURI();
    String path = requestURI.getPath();

    String operation = getOperationId(method, path);
    LOG.debug("Found operation id: {}", operation);

    getHandler(operation).handle(exchange);
  }

  private String getOperationId(String method, String path) {
    final String key = method + ":" + path;
    return operationIds.computeIfAbsent(
        key,
        k ->
            specification
                .getOperation(method, path)
                .map(Operation::operationId)
                .orElseThrow(() -> new OperationIdNotFoundException(method, path)));
  }

  private HttpHandler getHandler(String operationId) {
    return requestHandlers.getOrDefault(operationId, internalServerErrorHandler());
  }
}
