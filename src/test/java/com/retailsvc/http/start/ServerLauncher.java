package com.retailsvc.http.start;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.Gson;
import com.retailsvc.http.OpenApiServer;
import com.retailsvc.http.openapi.SpecificationLoader;
import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.RequestBodyMapper;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLauncher {

  private static final Logger LOG = LoggerFactory.getLogger(ServerLauncher.class);

  public static void main(String[] args) throws Exception {
    new ServerLauncher();
  }

  public ServerLauncher() throws IOException {
    long t0 = System.currentTimeMillis();
    String specificationContents = loadSpecification("openapi.json");
    OpenApi specification = parseSpecification(specificationContents);

    Map<String, HttpHandler> handlers = new HashMap<>();
    handlers.put("get-data", new GetDataHandler());
    handlers.put("post-data", new PostDataHandler());

    final Gson gson = new Gson();
    // TODO: better solution?! This way we support jackson and gson
    RequestBodyMapper mapper =
        new RequestBodyMapper() {
          @Override
          public <T> T mapFrom(byte[] body) {
            Class<Map> c = Map.class;
            return (T) gson.fromJson(new String(body), c);
          }
        };

    new OpenApiServer(specification, mapper, handlers, null);
    LOG.info("Application started in {}ms", System.currentTimeMillis() - t0);
  }

  private static String loadSpecification(String spec) {
    LOG.debug("Loading specification from '{}'...", spec);
    return new String(SpecificationLoader.load(spec), UTF_8);
  }

  private static OpenApi parseSpecification(String specificationContents) {
    /* TODO: this logic should be placed inside project code, and not in launcher */
    long t0 = System.currentTimeMillis();
    Function<String, OpenApi> mapper = json -> new Gson().fromJson(json, OpenApi.class);
    OpenApi spec = OpenApi.parse(mapper, specificationContents);

    LOG.debug(
        "Parsed OpenAPI {} specification in {}ms", spec.openapi(), System.currentTimeMillis() - t0);

    return spec;
  }
}
