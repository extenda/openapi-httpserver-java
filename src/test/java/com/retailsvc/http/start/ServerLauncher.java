package com.retailsvc.http.start;

import com.retailsvc.http.Handlers;
import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import com.retailsvc.http.spec.Spec;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class ServerLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(ServerLauncher.class);

  static void main() throws Exception {
    new ServerLauncher();
  }

  public ServerLauncher() throws IOException {
    long t0 = System.currentTimeMillis();

    Map<String, Object> raw;
    try (InputStream in = ServerLauncher.class.getResourceAsStream("/openapi.yaml")) {
      raw = new Yaml().load(in);
    }
    Spec spec = Spec.from(raw);

    Map<String, RequestHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());
    handlers.put("post-list-objects", new PostListObjectsHandler());
    handlers.put("query-params", new ParamHandler());
    handlers.put("path-params", new ParamHandler());
    handlers.put("path-params-multi", new ParamHandler());
    handlers.put("post-shape", req -> Response.status(200));
    handlers.put("post-filter", req -> Response.status(200));
    handlers.put("post-blocked", req -> Response.status(200));
    handlers.put("format-email", req -> Response.status(200));
    handlers.put("format-int32", req -> Response.status(200));
    handlers.put("format-byte", req -> Response.status(200));
    handlers.put("post-gate", req -> Response.status(200));
    handlers.put("form-echo", req -> Response.status(200));
    handlers.put("text-echo", req -> Response.status(200));
    handlers.put("secureApiKey", req -> Response.status(200));
    handlers.put("secureBearer", req -> Response.status(200));
    handlers.put("secureBasic", req -> Response.status(200));
    handlers.put("secureOpen", req -> Response.status(200));

    OpenApiServer.builder()
        .spec(spec)
        .handlers(handlers)
        .responseDecorator(Handlers.securityHeadersDecorator())
        .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
        .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
        .securityValidator("basicAuth", (req, cred) -> Optional.empty())
        .build();
    LOG.info("Application started in {}ms", System.currentTimeMillis() - t0);
  }
}
