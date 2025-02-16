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
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the complete OpenAPI specification.
 *
 * @author thced
 */
public record OpenApi(
    String openapi, Info info, Collection<Server> servers, Map<String, PathItem> paths) {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApi.class);
  private static final Set<String> SUPPORTED_VERSIONS = Set.of("3.1.0");

  public static OpenApi parse(Function<String, OpenApi> fn, String spec) {
    return fn.apply(spec);
  }

  public OpenApi {
    if (!SUPPORTED_VERSIONS.contains(openapi)) {
      throw new UnsupportedVersionException(openapi);
    }
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

  public Optional<Operation> getOperation(String method, String path) {
    LOG.debug("Finding operationId for {} {}...", method, path);
    String foundPath =
        servers.stream()
            .map(Server::baseUrl)
            .filter(path::startsWith)
            .map(s -> path.replace(s, ""))
            .findFirst()
            .orElse("");

    return paths.entrySet().stream()
        .filter(e -> e.getKey().equals(foundPath))
        .map(Entry::getValue)
        .map(pathItem -> pathItem.findByMethod(method))
        .filter(Objects::nonNull)
        .findFirst();
  }

  /**
   * The 'info' object.
   *
   * @param title The OpenAPI title
   * @param version The version of the OpenAPI specification
   * @see <a href="https://swagger.io/specification/#info-object">Info Object</a>
   */
  public record Info(String title, String version) {}

  /**
   * The 'server' object.
   *
   * @param url The server url
   * @see <a href="https://swagger.io/specification/#server-object">Server Object</a>
   */
  public record Server(String url) {
    /** Server url without host part */
    public String baseUrl() {
      return URI.create(url).getPath();
    }
  }

  /**
   * The 'path item' represents all the methods/operations available on a path.
   *
   * @param get The get operation
   * @param post The post operation
   * @param put The put operation
   * @param delete The delete operation
   * @see <a href="https://swagger.io/specification/#path-item-object">Path Item Object</a>
   */
  public record PathItem(Operation get, Operation post, Operation put, Operation delete) {
    public Operation findByMethod(String method) {
      return switch (method) {
        case "GET" -> get;
        case "POST" -> post;
        case "PUT" -> put;
        case "DELETE" -> delete;
        default -> null;
      };
    }
  }

  /**
   * Represents the 'operation' for a method type.
   *
   * @param operationId the id used to map a handler to this endpoint
   * @param responses The available responses that can be returned.
   * @see <a href="https://swagger.io/specification/#operation-object">Operation Object</a>
   */
  public record Operation(String operationId, RequestBody requestBody, Object responses) {}

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

  /**
   * Represents a supported 'media-type' for an endpoint.
   *
   * @param schema The schema defining the content of the request, response, or parameter
   * @see <a href="https://swagger.io/specification/#media-type-object">Media Type Object</a>
   */
  public record MediaType(Schema schema) {}

  public record Schema(
      String type,
      String format,
      Map<String, Object> properties,
      Map<String, Object> items,
      List<String> required,
      Number maximum,
      Number minimum) {

    public Schema {
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
}
