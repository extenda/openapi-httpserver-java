package com.retailsvc.http;

import static java.lang.Thread.ofVirtual;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.internal.ExceptionFilter;
import com.retailsvc.http.internal.RequestPreparationFilter;
import com.retailsvc.http.internal.Router;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
  private static final int DEFAULT_PORT = 8080;

  private final HttpServer httpServer;

  /**
   * @param spec The parsed {@link Spec}
   * @param jsonMapper Body deserializer
   * @param handlers Mappings between operationId and {@link HttpHandler}
   * @param exceptionHandler Error handler receiving exceptions thrown from a handler
   * @throws IOException If an error occurs during server start
   */
  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler)
      throws IOException {
    this(spec, jsonMapper, handlers, exceptionHandler, DEFAULT_PORT);
  }

  /**
   * @param spec The parsed {@link Spec}
   * @param jsonMapper Body deserializer
   * @param handlers Mappings between operationId and {@link HttpHandler}
   * @param exceptionHandler Error handler receiving exceptions thrown from a handler
   * @param port The server port to use
   * @throws IOException If an error occurs during server start
   */
  public OpenApiServer(
      Spec spec,
      JsonMapper jsonMapper,
      Map<String, HttpHandler> handlers,
      ExceptionHandler exceptionHandler,
      int port)
      throws IOException {

    requireNonNull(spec, "Spec must not be null");
    requireNonNull(jsonMapper, "JsonMapper must not be null");
    requireNonNull(handlers, "handlers must not be null");
    if (exceptionHandler == null) {
      LOG.warn("No ExceptionHandler set, using default");
      exceptionHandler = Handlers.defaultExceptionHandler();
    }

    long t0 = System.currentTimeMillis();
    Router router = new Router(spec.operations());
    DefaultValidator validator = new DefaultValidator(spec::resolveSchema);

    this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpServer.setExecutor(newThreadPerTaskExecutor(ofVirtual().name("http-", 0).factory()));

    HttpContext ctx = httpServer.createContext(Optional.ofNullable(spec.basePath()).orElse("/"));
    ctx.getFilters().add(new ExceptionFilter(exceptionHandler));
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, jsonMapper));
    ctx.setHandler(new DispatchHandler(handlers));

    httpServer.createContext("/", Handlers.notFoundHandler());
    httpServer.start();

    LOG.info("Server started (port {}) in {}ms", port, System.currentTimeMillis() - t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  @Override
  public void close() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }
}
