package com.retailsvc.http.start;

import com.google.gson.Gson;
import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Handlers;
import com.retailsvc.http.JsonMapper;
import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.spec.Spec;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
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

    final Gson gson = new Gson();

    Map<String, Object> raw;
    try (InputStream in = ServerLauncher.class.getResourceAsStream("/openapi.yaml")) {
      raw = new Yaml().load(in);
    }
    Spec spec = Spec.from(raw);

    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());
    handlers.put("post-list-objects", new PostListObjectsHandler());
    handlers.put("query-params", new ParamHandler());
    handlers.put("path-params", new ParamHandler());
    handlers.put("path-params-multi", new ParamHandler());

    JsonMapper mapper = body -> gson.fromJson(new String(body), Object.class);

    ExceptionHandler exceptionHandler = Handlers.defaultExceptionHandler();

    new OpenApiServer(spec, mapper, handlers, exceptionHandler);
    LOG.info("Application started in {}ms", System.currentTimeMillis() - t0);
  }
}
