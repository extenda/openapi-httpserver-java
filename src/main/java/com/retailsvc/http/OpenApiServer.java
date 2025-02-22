package com.retailsvc.http;

import static com.retailsvc.http.Handlers.notFoundHandler;
import static java.lang.Thread.ofVirtual;
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.openapi.OpenApiValidationFilter;
import com.retailsvc.http.openapi.RequestDispatchingHandler;
import com.retailsvc.http.openapi.model.JsonMapper;
import com.retailsvc.http.openapi.model.OpenApi;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Set up an {@link HttpServer} exposing endpoints declared in an OpenAPI 3.1.x specification.
 *
 * @author thced
 */
public class OpenApiServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApiServer.class);
  private static final int PORT = 8080;

  private final HttpServer httpServer;

  /**
   * @param specification The {@link OpenApi} specification
   * @param requestHandlers The mappings between operationId and {@link HttpHandler}
   * @param exceptionHandler Error handler receiving exception being thrown from a handler
   * @throws IOException If an error occur during server start
   */
  public OpenApiServer(
      OpenApi specification,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> requestHandlers,
      ExceptionHandler exceptionHandler)
      throws IOException {
    this(specification, jsonMapper, requestHandlers, exceptionHandler, PORT);
  }

  /**
   * @param specification The {@link OpenApi} specification
   * @param requestHandlers The mappings between operationId and {@link HttpHandler}
   * @param exceptionHandler Error handler receiving exception being thrown from a handler
   * @param httpPort The server port to use
   * @throws IOException If an error occur during server start
   */
  public OpenApiServer(
      OpenApi specification,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> requestHandlers,
      ExceptionHandler exceptionHandler,
      int httpPort)
      throws IOException {

    long t0 = System.currentTimeMillis();
    LOG.debug("Starting server...");

    requireNonNull(specification, "OpenAPI specification must not be null");
    requireNonNull(jsonMapper, "Request body mapper must not be null");
    requireNonNull(requestHandlers, "Request handlers must not be null");

    if (isNull(exceptionHandler)) {
      LOG.warn("No exception handler set, using default.");
      exceptionHandler = Handlers.defaultExceptionHandler();
    }

    httpServer =
        initializeServer(
            httpPort, specification, jsonMapper, requestHandlers, exceptionHandler, t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  private HttpServer initializeServer(
      int port,
      OpenApi specification,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> requestHandlers,
      ExceptionHandler errorHandler,
      long t0)
      throws IOException {

    HttpServer server = createHttpServer(port);
    HttpContext context = server.createContext(specification.basePath());

    List<Filter> filters = context.getFilters();
    filters.add(new ExceptionHandlingFilter(errorHandler));
    filters.add(new BodyHandler());
    filters.add(new OpenApiValidationFilter(specification, jsonMapper));

    context.setHandler(new RequestDispatchingHandler(requestHandlers));

    server.createContext("/", notFoundHandler());
    server.start();

    LOG.info("Server started (port {}) in {}ms", PORT, System.currentTimeMillis() - t0);

    return server;
  }

  @Override
  public void close() {
    Optional.ofNullable(httpServer).ifPresent(server -> server.stop(0));
  }

  private HttpServer createHttpServer(int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(newThreadPerTaskExecutor(ofVirtual().name("http-", 0).factory()));
    return server;
  }
}
