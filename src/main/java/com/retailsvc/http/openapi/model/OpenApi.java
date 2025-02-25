package com.retailsvc.http.openapi.model;

import com.retailsvc.http.openapi.exceptions.NoServersDeclaredException;
import com.retailsvc.http.openapi.exceptions.UnsupportedVersionException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the complete OpenAPI specification.
 *
 * @author thced
 */
public record OpenApi(
    String openapi,
    Info info,
    Collection<Server> servers,
    Map<String, PathItem> paths,
    Components components) {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApi.class);

  /** Parses OpenAPI specification using provided parser function. */
  public static OpenApi parse(Function<String, OpenApi> fn, String spec) {
    return fn.apply(spec);
  }

  public OpenApi {
    validateVersion(openapi);
  }

  public String stripBasePath(String path) {
    return path.replace(basePath(), "");
  }

  public String basePath() {
    return servers.stream()
        .findFirst()
        .map(Server::url)
        .map(URI::create)
        .map(URI::getPath)
        .orElseThrow(NoServersDeclaredException::new);
  }

  public Optional<Operation> findOperation(String method, String path) {
    LOG.debug("Finding operationId for {} {}...", method, path);
    String normalizedPath = normalizePath(path);
    return paths.entrySet().stream()
        .filter(e -> e.getKey().equals(normalizedPath))
        .map(Entry::getValue)
        .map(pathItem -> pathItem.findByMethod(method))
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * Used to get access to the referenced schema components. It will strip off the
   * '#/components/schemas/' prefix and cache the found {@link Schema} instance.
   *
   * @param ref The "full" ref name
   * @return The found schema, or null
   */
  public Schema resolveSchema(String ref) {
    String name = ref.replace("#/components/schemas/", "");
    Schema found = components.getSchema(name);
    LOG.debug("Found resolved schema: {} -> {}", ref, found);
    return found;
  }

  /**
   * Used to get access to the referenced parameter schema components. It will strip off the
   * '#/components/parameters/' prefix and cache the found {@link Schema} instance.
   *
   * @param ref The "full" ref name
   * @return The found schema, or null
   */
  public Parameter resolveParameter(String ref) {
    String name = ref.replace("#/components/parameters/", "");
    Parameter parameter = components.getParameter(name);
    LOG.debug("Found resolved parameter: {} -> {}", ref, parameter);
    return parameter;
  }

  private String normalizePath(String path) {
    return servers.stream()
        .map(Server::baseUrl)
        .filter(path::startsWith)
        .map(baseUrl -> path.replace(baseUrl, ""))
        .findFirst()
        .orElse("");
  }

  private void validateVersion(String version) {
    if (!OpenApiConstants.SUPPORTED_VERSIONS.contains(version)) {
      throw new UnsupportedVersionException(version);
    }
  }
}
