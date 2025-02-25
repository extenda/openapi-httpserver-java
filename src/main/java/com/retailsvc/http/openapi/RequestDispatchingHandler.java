package com.retailsvc.http.openapi;

import static java.util.Objects.requireNonNull;

import com.retailsvc.http.openapi.exceptions.MissingOperationHandlerException;
import com.retailsvc.http.openapi.exceptions.OperationIdNotFoundException;
import com.retailsvc.http.openapi.model.Operation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
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

  private final Map<String, HttpHandler> requestHandlers;

  public RequestDispatchingHandler(Map<String, HttpHandler> requestHandlers) {
    LOG.debug("Instantiating RequestDispatchingHandler...");
    this.requestHandlers = requireNonNull(requestHandlers);
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
    var operationId = (String) exchange.getAttribute(Operation.OPERATION_ID);

    if (operationId == null) {
      throw new OperationIdNotFoundException(
          exchange.getRequestMethod(), exchange.getRequestURI().getPath());
    }

    var handler = getHandler(operationId);
    LOG.debug("Calling handler for operation-id [{}]", operationId);
    handler.handle(exchange);
  }

  private HttpHandler getHandler(String operationId) {
    return Optional.ofNullable(requestHandlers.get(operationId))
        .orElseThrow(() -> new MissingOperationHandlerException(operationId));
  }
}
