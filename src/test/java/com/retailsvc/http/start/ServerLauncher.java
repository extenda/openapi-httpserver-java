package com.retailsvc.http.start;

import static com.retailsvc.http.openapi.SpecificationLoader.parseSpecification;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retailsvc.http.ExceptionHandler;
import com.retailsvc.http.Handlers;
import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.openapi.model.JsonMapper;
import com.retailsvc.http.openapi.model.OpenApi;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(ServerLauncher.class);

  static void main() throws Exception {
    new ServerLauncher();
  }

  public ServerLauncher() throws IOException {
    long t0 = System.currentTimeMillis();

    final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    Function<String, OpenApi> jsonToSpec = contents -> gson.fromJson(contents, OpenApi.class);
    Function<Object, String> toJson = gson::toJson;

    var specification = parseSpecification("openapi.yaml", jsonToSpec, toJson);

    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());
    handlers.put("post-list-objects", new PostListObjectsHandler());
    handlers.put("query-params", new ParamHandler());
    handlers.put("path-params", new ParamHandler());
    handlers.put("path-params-multi", new ParamHandler());

    JsonMapper mapper =
        new JsonMapper() {
          @Override
          public <T> T mapFrom(byte[] body) {
            if (body.length > 0 && body[0] == '[') {
              return (T) gson.fromJson(new String(body), List.class);
            }
            return (T) gson.fromJson(new String(body), Map.class);
          }
        };

    ExceptionHandler exceptionHandler = Handlers.defaultExceptionHandler();

    new OpenApiServer(specification, mapper, handlers, exceptionHandler);
    LOG.info("Application started in {}ms", System.currentTimeMillis() - t0);
  }
}
