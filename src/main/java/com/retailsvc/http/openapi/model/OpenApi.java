package com.retailsvc.http.openapi.model;

import static java.util.Objects.isNull;

import com.retailsvc.http.openapi.exceptions.LoadSpecificationException;
import com.retailsvc.http.openapi.exceptions.NoServersDeclaredException;
import com.retailsvc.http.openapi.exceptions.UnsupportedVersionException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
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

  /**
   * Represents the 'requestBody' for an endpoint.
   *
   * @param description The description for the request body
   * @param content The map of media types the endpoint supports
   * @param required The required properties for this request body
   * @see <a href="https://swagger.io/specification/#request-body-object">Request Body Object</a>
   */
  public record RequestBody(
      String description, Map<String, MediaType> content, List<String> required) {
    public RequestBody {
      required = Objects.requireNonNullElse(required, List.of());
    }
  }

  public record Parameter(String $ref, String in, String name, boolean required, Schema schema) {

    private static final String HEADER = "header";
    private static final String QUERY = "query";
    private static final String PATH = "path";

    public boolean isHeader() {
      return in != null && in.equalsIgnoreCase(HEADER);
    }

    public boolean isPath() {
      return in != null && in.equalsIgnoreCase(PATH);
    }

    public boolean isQuery() {
      return in != null && in.equalsIgnoreCase(QUERY);
    }
  }

  /**
   * Represents a supported 'media-type' for an endpoint.
   *
   * @param schema The schema defining the content of the request, response, or parameter
   * @see <a href="https://swagger.io/specification/#media-type-object">Media Type Object</a>
   */
  public record MediaType(Schema schema) {}

  public record Schema(
      String $ref,
      String type,
      String format,
      String pattern,
      Map<String, Object> properties,
      Map<String, Object> items,
      List<String> required,
      Number maximum,
      Number minimum) {

    /**
     * If Schema has a $ref, we do not set any properties. The properties will be resolved later via
     * the referenced component {@link Components}.
     */
    public Schema {
      if (isNull($ref)) {
        if (type == null || type.isBlank()) {
          throw new LoadSpecificationException("Type is missing");
        }
        if (isNull(format) && isNumber()) {
          format = "int32";
        }
        required = Objects.requireNonNullElse(required, List.of());
        items = Objects.requireNonNullElse(items, Map.of());
        properties = Objects.requireNonNullElse(properties, Map.of());
        maximum = Objects.requireNonNullElse(maximum, Double.MAX_VALUE);
        minimum = Objects.requireNonNullElse(minimum, Double.MIN_VALUE);
      }
    }

    public Schema(
        String type,
        String format,
        String pattern,
        Map<String, Object> properties,
        Map<String, Object> items,
        List<String> required,
        Number maximum,
        Number minimum) {
      this(null, type, format, pattern, properties, items, required, maximum, minimum);
    }

    public boolean isString() {
      return "string".equalsIgnoreCase(type);
    }

    public boolean isBoolean() {
      return "boolean".equalsIgnoreCase(type);
    }

    public boolean isInteger() {
      return isNumber() && Optional.ofNullable(format).map("int32"::equalsIgnoreCase).orElse(true);
    }

    public boolean isLong() {
      return isNumber() && Optional.ofNullable(format).map("int64"::equalsIgnoreCase).orElse(false);
    }

    public boolean isNumber() {
      return "number".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type);
    }

    public boolean isObject() {
      return "object".equalsIgnoreCase(type);
    }

    public boolean isArray() {
      return "array".equalsIgnoreCase(type);
    }
  }

  public record Components(Map<String, Schema> schemas, Map<String, Parameter> parameters) {
    public Schema getSchema(String name) {
      return schemas.get(name);
    }

    public Parameter getParameter(String name) {
      return parameters.get(name);
    }
  }
}
