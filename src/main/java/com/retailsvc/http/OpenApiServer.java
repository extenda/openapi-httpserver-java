package com.retailsvc.http;

import static java.lang.Thread.ofVirtual;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.retailsvc.http.internal.DispatchHandler;
import com.retailsvc.http.internal.ExceptionFilter;
import com.retailsvc.http.internal.ExtrasRouter;
import com.retailsvc.http.internal.FormTypeMapper;
import com.retailsvc.http.internal.PemSslContext;
import com.retailsvc.http.internal.RequestPreparationFilter;
import com.retailsvc.http.internal.ResponseRenderer;
import com.retailsvc.http.internal.SecurityFilter;
import com.retailsvc.http.internal.SpecBinding;
import com.retailsvc.http.internal.TextTypeMapper;
import com.retailsvc.http.internal.TlsHttpsConfigurator;
import com.retailsvc.http.internal.gson.GsonJsonMapper;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
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
  private static final int DEFAULT_HTTPS_PORT = 8443;
  private static final String JSON = "application/json";
  private static final String GSON_CLASS = "com.google.gson.Gson";

  private final HttpServer httpServer;
  private final int shutdownTimeoutSeconds;

  /** Internal grouping of handler-related configuration to keep the constructor signature small. */
  record HandlerConfig(
      List<RequestInterceptor> interceptors,
      List<ResponseDecorator> decorators,
      ExceptionHandler exceptionHandler,
      Map<String, RequestHandler> extras,
      boolean externalAuth,
      List<AfterResponseHook> afterHooks) {}

  OpenApiServer(
      List<SpecBinding> bindings,
      Map<String, TypeMapper> bodyMappers,
      HandlerConfig handlerConfig,
      int port,
      InetAddress bindAddress,
      int shutdownTimeoutSeconds,
      SSLContext sslContext)
      throws IOException {

    requireNonNull(bindings, "bindings must not be null");
    if (bindings.isEmpty()) {
      throw new IllegalStateException("at least one spec binding is required");
    }
    requireNonNull(bodyMappers, "bodyMappers must not be null");
    ExceptionHandler exceptionHandler = handlerConfig.exceptionHandler();

    long t0 = System.currentTimeMillis();

    InetSocketAddress socketAddress =
        (bindAddress == null)
            ? new InetSocketAddress(port)
            : new InetSocketAddress(bindAddress, port);
    if (sslContext != null) {
      HttpsServer https = HttpsServer.create(socketAddress, 0);
      https.setHttpsConfigurator(new TlsHttpsConfigurator(sslContext));
      this.httpServer = https;
    } else {
      this.httpServer = HttpServer.create(socketAddress, 0);
    }
    httpServer.setExecutor(newThreadPerTaskExecutor(ofVirtual().name("http-", 0).factory()));

    ResponseRenderer renderer = new ResponseRenderer(bodyMappers);

    boolean anyBindingAtRoot = false;
    for (SpecBinding binding : bindings) {
      String basePath = Optional.ofNullable(binding.spec().basePath()).orElse("/");
      anyBindingAtRoot |= "/".equals(basePath);
      Map<String, Operation> operationsById =
          binding.spec().operations().stream()
              .collect(Collectors.toUnmodifiableMap(Operation::operationId, op -> op));
      HttpContext ctx = httpServer.createContext(basePath);
      ctx.getFilters()
          .add(
              new RequestPreparationFilter(
                  binding.spec(),
                  binding.router(),
                  binding.validator(),
                  bodyMappers,
                  exceptionHandler,
                  renderer,
                  handlerConfig.afterHooks()));
      ctx.getFilters()
          .add(
              new SecurityFilter(
                  operationsById,
                  binding.spec().securitySchemes(),
                  binding.spec().security(),
                  binding.securityValidators(),
                  handlerConfig.externalAuth()));
      ctx.setHandler(
          new DispatchHandler(
              binding.handlers(),
              handlerConfig.interceptors(),
              handlerConfig.decorators(),
              renderer));
    }

    if (!anyBindingAtRoot) {
      ExtrasRouter extrasRouter = new ExtrasRouter(handlerConfig.extras(), renderer);
      HttpContext extrasCtx = httpServer.createContext("/", extrasRouter);
      extrasCtx.getFilters().add(new ExceptionFilter(exceptionHandler, renderer));
    } else if (!handlerConfig.extras().isEmpty()) {
      throw new IllegalStateException(
          "extras cannot be registered when a binding owns basePath '/'");
    }
    httpServer.start();

    this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;

    String host = httpServer.getAddress().getHostString();
    String displayHost = host.contains(":") ? "[" + host + "]" : host;
    LOG.info(
        "Server started ({}:{}) in {}ms",
        displayHost,
        httpServer.getAddress().getPort(),
        System.currentTimeMillis() - t0);
  }

  public int listenPort() {
    return httpServer.getAddress().getPort();
  }

  /**
   * Returns the local address the server is bound to. For a wildcard-bound server this is the
   * wildcard address; for a loopback-bound server this is the loopback address.
   */
  public InetAddress bindAddress() {
    return httpServer.getAddress().getAddress();
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
    private final List<AfterResponseHook> afterHooks = new ArrayList<>();
    private ExceptionHandler exceptionHandler;
    private Integer port;
    private Path httpsCertChain;
    private Path httpsPrivateKey;
    private InetAddress bindAddress;
    private int shutdownTimeoutSeconds = 0;
    private final LinkedHashMap<String, RequestHandler> extras = new LinkedHashMap<>();
    private final Map<String, SchemeValidator> securityValidators = new LinkedHashMap<>();
    private boolean externalAuth = false;
    private final List<SpecBinding> bindings = new ArrayList<>();

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

    public Builder jsonMapper(TypeMapper mapper) {
      return bodyMapper(JSON, mapper);
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

    /**
     * Registers an {@link AfterResponseHook} invoked after each response is sent. Hooks run on the
     * request thread inside the library's request scope, in registration order, with all exceptions
     * swallowed. Hooks fire only when a {@link Request} was successfully built — pre-request
     * failures (404, 405, 400 validation) do not fire hooks.
     */
    public Builder afterResponseHook(AfterResponseHook hook) {
      afterHooks.add(requireNonNull(hook, "hook must not be null"));
      return this;
    }

    /**
     * Registers a {@link SchemeValidator} for the OpenAPI security scheme named {@code schemeName}.
     * The library extracts a {@link Credential} per request and hands it to this callback; return a
     * non-empty {@link Optional} carrying the principal on success, or {@link Optional#empty()} to
     * deny. Library renders 401/403 on denial.
     */
    public Builder securityValidator(String schemeName, SchemeValidator validator) {
      requireNonNull(schemeName, "schemeName must not be null");
      requireNonNull(validator, "validator must not be null");
      securityValidators.put(schemeName, validator);
      return this;
    }

    /**
     * Opts out of in-process security enforcement. Use when an external sidecar (OPA/Envoy etc.)
     * authenticates requests upstream. The library still parses {@code securitySchemes} into the
     * {@link Spec}, but {@code SecurityFilter} short-circuits and the boot-time
     * validator-registration check is skipped.
     */
    public Builder useExternalAuthentication() {
      this.externalAuth = true;
      return this;
    }

    /**
     * Registers an OpenAPI {@link Spec} with the handlers and security validators that serve it.
     * May be called more than once; each binding becomes its own {@link
     * com.sun.net.httpserver.HttpContext} at the spec's {@code basePath}. {@code operationId}s and
     * security-scheme names only need to be unique within a single spec.
     */
    public Builder addSpec(
        Spec spec,
        Map<String, RequestHandler> handlers,
        Map<String, SchemeValidator> securityValidators) {
      requireNonNull(spec, "spec must not be null");
      requireNonNull(handlers, "handlers must not be null");
      requireNonNull(securityValidators, "securityValidators must not be null");
      bindings.add(SpecBinding.of(spec, handlers, securityValidators));
      return this;
    }

    /** Convenience overload for specs that declare no security schemes. */
    public Builder addSpec(Spec spec, Map<String, RequestHandler> handlers) {
      return addSpec(spec, handlers, Map.of());
    }

    public Builder exceptionHandler(ExceptionHandler exceptionHandler) {
      this.exceptionHandler = exceptionHandler;
      return this;
    }

    /**
     * Sets the TCP port to listen on. Defaults to {@value #DEFAULT_PORT} for HTTP and {@value
     * #DEFAULT_HTTPS_PORT} when {@link #https(Path, Path)} is set. Use {@code 0} to bind on an
     * ephemeral port (read it back via {@link OpenApiServer#listenPort()}).
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Restricts the server to a specific local interface. {@code null} (the default) binds to the
     * wildcard address (all interfaces). Use {@link InetAddress#getLoopbackAddress()} to listen on
     * loopback only.
     */
    public Builder bindAddress(InetAddress bindAddress) {
      this.bindAddress = bindAddress;
      return this;
    }

    /**
     * Enables HTTPS using the given PEM-encoded certificate chain and PKCS#8 private key. Both
     * files must exist when {@link #build()} runs; failures surface as {@link
     * IllegalStateException} with the offending path. The certificate file is a PEM concatenation
     * of the server certificate followed by any intermediates (matches certbot's {@code
     * fullchain.pem}). The private key is an unencrypted PKCS#8 PEM (matches certbot's {@code
     * privkey.pem}); RSA and EC keys are both accepted.
     *
     * <p>When set, the default port changes from {@value #DEFAULT_PORT} to {@value
     * #DEFAULT_HTTPS_PORT}; {@link #port(int)} still overrides.
     */
    public Builder https(Path certificateChainPem, Path privateKeyPem) {
      this.httpsCertChain =
          requireNonNull(certificateChainPem, "certificateChainPem must not be null");
      this.httpsPrivateKey = requireNonNull(privateKeyPem, "privateKeyPem must not be null");
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

    /**
     * Registers an extra HTTP route at {@code path} that bypasses OpenAPI validation and routing.
     * Use for side concerns like {@code /alive}, {@code /health}, or serving the spec itself —
     * anything that isn't an OpenAPI {@code operationId}. For OpenAPI-described operations use
     * {@link #handlers(Map)}.
     */
    public Builder extraRoute(String path, RequestHandler handler) {
      requireNonNull(path, "path must not be null");
      requireNonNull(handler, "handler must not be null");
      if (extras.containsKey(path)) {
        throw new IllegalStateException("duplicate extra route path: " + path);
      }
      extras.put(path, handler);
      return this;
    }

    public OpenApiServer build() throws IOException {
      boolean usedLegacy = spec != null || handlers != null || !securityValidators.isEmpty();
      boolean usedAddSpec = !bindings.isEmpty();
      if (usedLegacy && usedAddSpec) {
        throw new IllegalStateException(
            "use either spec()/handler()/securityValidator() or addSpec(), not both");
      }
      List<SpecBinding> effectiveBindings;
      if (usedAddSpec) {
        effectiveBindings = List.copyOf(bindings);
      } else {
        requireNonNull(spec, "Spec must not be null");
        requireNonNull(handlers, "handlers must not be null");
        effectiveBindings = List.of(SpecBinding.of(spec, handlers, securityValidators));
      }

      for (SpecBinding b : effectiveBindings) {
        if (!externalAuth) {
          validateSecurityWiring(b.spec(), b.securityValidators());
        }
        validateHandlerWiring(b.spec(), b.handlers());
      }

      Map<String, String> seenBasePaths = new LinkedHashMap<>();
      for (SpecBinding b : effectiveBindings) {
        String bp = Optional.ofNullable(b.spec().basePath()).orElse("/");
        String existingTitle = seenBasePaths.putIfAbsent(bp, b.spec().info().title());
        if (existingTitle != null) {
          throw new IllegalStateException(
              "duplicate basePath '"
                  + bp
                  + "' across specs: '"
                  + existingTitle
                  + "' and '"
                  + b.spec().info().title()
                  + "'");
        }
      }

      for (String path : extras.keySet()) {
        if (seenBasePaths.containsKey(path)) {
          throw new IllegalStateException(
              "extra handler path '"
                  + path
                  + "' conflicts with basePath of spec '"
                  + seenBasePaths.get(path)
                  + "'");
        }
      }

      Map<String, TypeMapper> resolved = resolveBodyMappers(bodyMappers);
      ExceptionHandler effectiveExceptionHandler =
          exceptionHandler != null ? exceptionHandler : Handlers.defaultExceptionHandler();
      HandlerConfig handlerConfig =
          new HandlerConfig(
              interceptors,
              decorators,
              effectiveExceptionHandler,
              extras,
              externalAuth,
              List.copyOf(afterHooks));
      int resolvedPort = resolvePort();
      SSLContext sslContext =
          httpsCertChain != null ? PemSslContext.load(httpsCertChain, httpsPrivateKey) : null;
      return new OpenApiServer(
          effectiveBindings,
          resolved,
          handlerConfig,
          resolvedPort,
          bindAddress,
          shutdownTimeoutSeconds,
          sslContext);
    }

    private int resolvePort() {
      if (port != null) {
        return port;
      }
      return httpsCertChain != null ? DEFAULT_HTTPS_PORT : DEFAULT_PORT;
    }

    private static void validateHandlerWiring(Spec spec, Map<String, RequestHandler> handlers) {
      Set<String> specOps = new TreeSet<>();
      for (Operation op : spec.operations()) {
        specOps.add(op.operationId());
      }
      Set<String> missing = new TreeSet<>(specOps);
      missing.removeAll(handlers.keySet());
      if (!missing.isEmpty()) {
        throw new IllegalStateException(
            "no handler registered for spec operationId(s): " + missing);
      }
      Set<String> unknown = new TreeSet<>(handlers.keySet());
      unknown.removeAll(specOps);
      if (!unknown.isEmpty()) {
        throw new IllegalStateException(
            "handler registered for unknown operationId(s) not in spec: " + unknown);
      }
    }

    private static void validateSecurityWiring(Spec spec, Map<String, SchemeValidator> validators) {
      Set<String> referenced = new LinkedHashSet<>();
      for (Operation op : spec.operations()) {
        for (SecurityRequirement req : op.security().orElse(spec.security())) {
          referenced.addAll(req.schemes().keySet());
        }
      }
      for (String name : referenced) {
        SecurityScheme scheme = spec.securitySchemes().get(name);
        if (scheme == null) {
          throw new IllegalStateException(
              "security requirement references unknown scheme '" + name + "'");
        }
        if (scheme instanceof SecurityScheme.Unsupported(String type)) {
          throw new IllegalStateException(
              "scheme '" + name + "' uses unsupported type '" + type + "'");
        }
        if (!validators.containsKey(name)) {
          throw new IllegalStateException(
              "no SchemeValidator registered for security scheme '" + name + "'");
        }
      }
    }

    private static Map<String, TypeMapper> resolveBodyMappers(
        Map<String, TypeMapper> userSupplied) {
      LinkedHashMap<String, TypeMapper> out = new LinkedHashMap<>();
      out.put("application/x-www-form-urlencoded", new FormTypeMapper());
      out.put("text/plain", new TextTypeMapper());
      out.put("text/html", new TextTypeMapper());
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
      return new GsonJsonMapper();
    }
  }
}
