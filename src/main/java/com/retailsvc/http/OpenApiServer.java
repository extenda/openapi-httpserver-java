package com.retailsvc.http;

import static java.lang.Thread.ofVirtual;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.internal.ExceptionFilter;
import com.retailsvc.http.internal.FormTypeMapper;
import com.retailsvc.http.internal.RequestPreparationFilter;
import com.retailsvc.http.internal.ResponseRenderer;
import com.retailsvc.http.internal.Router;
import com.retailsvc.http.internal.TextTypeMapper;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  private static final String JSON = "application/json";
  private static final String GSON_CLASS = "com.google.gson.Gson";
  private static final String GSON_MAPPER_CLASS = "com.retailsvc.http.internal.gson.GsonJsonMapper";

  private final HttpServer httpServer;
  private final int shutdownTimeoutSeconds;

  OpenApiServer(
      Spec spec,
      Map<String, TypeMapper> bodyMappers,
      Map<String, RequestHandler> handlers,
      List<ResponseDecorator> decorators,
      List<RequestInterceptor> interceptors,
      ExceptionHandler exceptionHandler,
      int port,
      Map<String, HttpHandler> extras,
      int shutdownTimeoutSeconds)
      throws IOException {

    requireNonNull(spec, "Spec must not be null");
    requireNonNull(bodyMappers, "bodyMappers must not be null");
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
    ctx.getFilters().add(new RequestPreparationFilter(spec, router, validator, bodyMappers));
    ctx.setHandler(
        new DispatchHandler(handlers, interceptors, decorators, new ResponseRenderer(bodyMappers)));

    for (Map.Entry<String, HttpHandler> e : extras.entrySet()) {
      HttpContext extraCtx = httpServer.createContext(e.getKey());
      extraCtx.getFilters().add(new ExceptionFilter(exceptionHandler));
      extraCtx.setHandler(e.getValue());
    }

    httpServer.createContext("/", Handlers.notFoundHandler());
    httpServer.start();

    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

    LOG.info("Server started (port {}) in {}ms", port, System.currentTimeMillis() - t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  /**
   * Stops the server, waiting up to {@code delaySeconds} for active exchanges to finish before
   * closing them. {@code 0} stops immediately.
   *
   * @param delaySeconds maximum seconds to wait for in-flight exchanges; must be non-negative
   */
  public void stop(int delaySeconds) {
    if (delaySeconds < 0) {
      throw new IllegalArgumentException("delaySeconds must be non-negative, got " + delaySeconds);
    }
    if (httpServer != null) {
      httpServer.stop(delaySeconds);
    }
  }

  @Override
  public void close() {
    stop(shutdownTimeoutSeconds);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link OpenApiServer}. */
  public static final class Builder {

    private Spec spec;
    private final LinkedHashMap<String, TypeMapper> bodyMappers = new LinkedHashMap<>();
    private Map<String, RequestHandler> handlers;
    private final List<ResponseDecorator> decorators = new ArrayList<>();
    private final List<RequestInterceptor> interceptors = new ArrayList<>();
    private ExceptionHandler exceptionHandler;
    private int port = DEFAULT_PORT;
    private int shutdownTimeoutSeconds = 0;
    private final LinkedHashMap<String, HttpHandler> extras = new LinkedHashMap<>();

    private Builder() {}

    public Builder spec(Spec spec) {
      this.spec = spec;
      return this;
    }

    public Builder bodyMapper(String mediaType, TypeMapper mapper) {
      requireNonNull(mediaType, "mediaType must not be null");
      requireNonNull(mapper, "mapper must not be null");
      bodyMappers.put(mediaType.toLowerCase(Locale.ROOT), mapper);
      return this;
    }

    public Builder handlers(Map<String, RequestHandler> handlers) {
      this.handlers = handlers;
      return this;
    }

    /**
     * Registers a {@link ResponseDecorator} that transforms the {@link Response} returned by the
     * handler before it is rendered. Decorators compose in registration order; decorator-supplied
     * headers override handler-supplied ones on conflict.
     */
    public Builder responseDecorator(ResponseDecorator decorator) {
      decorators.add(requireNonNull(decorator, "decorator must not be null"));
      return this;
    }

    /**
     * Registers a {@link RequestInterceptor} that wraps the handler invocation. Interceptors run in
     * registration order; the first registered is the outermost.
     */
    public Builder interceptor(RequestInterceptor interceptor) {
      interceptors.add(requireNonNull(interceptor, "interceptor must not be null"));
      return this;
    }

    public Builder exceptionHandler(ExceptionHandler exceptionHandler) {
      this.exceptionHandler = exceptionHandler;
      return this;
    }

    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the default drain timeout used by {@link OpenApiServer#close()}. {@code 0} (the default)
     * stops immediately; positive values wait up to that many seconds for in-flight exchanges to
     * finish.
     */
    public Builder shutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
      if (shutdownTimeoutSeconds < 0) {
        throw new IllegalArgumentException(
            "shutdownTimeoutSeconds must be non-negative, got " + shutdownTimeoutSeconds);
      }
      this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
      return this;
    }

    public Builder addHandler(String path, HttpHandler handler) {
      requireNonNull(path, "path must not be null");
      requireNonNull(handler, "handler must not be null");
      if (extras.containsKey(path)) {
        throw new IllegalStateException("duplicate extra handler path: " + path);
      }
      extras.put(path, handler);
      return this;
    }

    public OpenApiServer build() throws IOException {
      requireNonNull(spec, "Spec must not be null");
      requireNonNull(handlers, "handlers must not be null");
      String basePath = Optional.ofNullable(spec.basePath()).orElse("/");
      for (String path : extras.keySet()) {
        if (path.equals(basePath)) {
          throw new IllegalStateException(
              "extra handler path " + path + " conflicts with spec basePath " + basePath);
        }
      }
      Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
      return new OpenApiServer(
          spec,
          resolved,
          handlers,
          decorators,
          interceptors,
          exceptionHandler,
          port,
          extras,
          shutdownTimeoutSeconds);
    }

    private static Map<String, TypeMapper> resolveBodyMappers(
        Map<String, TypeMapper> userSupplied) {
      LinkedHashMap<String, TypeMapper> out = new LinkedHashMap<>();
      out.put("application/x-www-form-urlencoded", new FormTypeMapper());
      out.put("text/plain", new TextTypeMapper());
      out.putAll(userSupplied);
      if (!out.containsKey(JSON)) {
        TypeMapper fallback = tryLoadGsonMapper();
        if (fallback != null) {
          out.put(JSON, fallback);
        }
      }
      if (!out.containsKey(JSON)) {
        throw new IllegalStateException(
            "No TypeMapper registered for application/json and Gson not found on classpath; "
                + "register one via Builder.bodyMapper(\"application/json\", ...)");
      }
      return out;
    }

    private static TypeMapper tryLoadGsonMapper() {
      try {
        Class.forName(GSON_CLASS, false, OpenApiServer.class.getClassLoader());
      } catch (ClassNotFoundException _) {
        return null;
      }
      try {
        Class<?> cls = Class.forName(GSON_MAPPER_CLASS, true, OpenApiServer.class.getClassLoader());
        return (TypeMapper) cls.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Failed to load " + GSON_MAPPER_CLASS, e);
      }
    }
  }
}
