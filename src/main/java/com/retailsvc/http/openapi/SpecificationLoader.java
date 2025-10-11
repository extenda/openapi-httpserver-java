package com.retailsvc.http.openapi;

import com.retailsvc.http.openapi.exceptions.LoadSpecificationException;
import com.retailsvc.http.openapi.model.OpenApi;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

public class SpecificationLoader {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static byte[] load(String spec) {
    LOG.debug("Loading specification from '{}'...", spec);
    try (InputStream is = loadFile(spec)) {
      return is.readAllBytes();
    } catch (Exception e) {
      String message = "Specification %s could not be loaded".formatted(spec);
      throw new LoadSpecificationException(message, e);
    }
  }

  /**
   * @param specificationPath The path to OpenAPI specification
   * @param mapper The mapper to serialize spec into an instance of {@link OpenApi}
   * @return The openapi model
   */
  public static OpenApi parseSpecification(
      String specificationPath, Function<String, OpenApi> mapper, Function<Object, String> toJson) {
    long t0 = System.currentTimeMillis();
    byte[] data = load(specificationPath);
    String openapiAsText = new String(data, StandardCharsets.UTF_8);

    if (specificationPath.endsWith(".yaml") || specificationPath.endsWith(".yml")) {
      var yaml = new Yaml();
      Object yamlObj = yaml.load(openapiAsText);
      openapiAsText = toJson.apply(yamlObj);
    }

    OpenApi spec = OpenApi.parse(mapper, openapiAsText);

    LOG.debug(
        "Parsed OpenAPI {} specification in {}ms", spec.openapi(), System.currentTimeMillis() - t0);

    return spec;
  }

  private static InputStream loadFile(String spec) {
    return SpecificationLoader.class.getClassLoader().getResourceAsStream(spec);
  }

  private SpecificationLoader() {}
}
