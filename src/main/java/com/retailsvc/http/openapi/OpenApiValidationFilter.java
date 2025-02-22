package com.retailsvc.http.openapi;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.retailsvc.http.openapi.exceptions.OperationIdNotFoundException;
import com.retailsvc.http.openapi.model.GetRequestBody;
import com.retailsvc.http.openapi.model.JsonMapper;
import com.retailsvc.http.openapi.model.MediaType;
import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.Operation;
import com.retailsvc.http.openapi.model.Parameter;
import com.retailsvc.http.openapi.model.PathItem;
import com.retailsvc.http.openapi.model.Schema;
import com.retailsvc.http.openapi.validation.Validator;
import com.retailsvc.http.openapi.validation.ValidatorImpl;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates incoming requests against the OpenAPI specification
 *
 * @author thced
 */
public class OpenApiValidationFilter extends Filter implements GetRequestBody {

  private static final Logger LOG = LoggerFactory.getLogger(OpenApiValidationFilter.class);
  private final OpenApi specification;
  private final Map<String, Operation> operationsByPath;
  private final JsonMapper mapper;
  private final Validator validator;

  public OpenApiValidationFilter(OpenApi spec, JsonMapper mapper) {
    this(spec, mapper, new ValidatorImpl(spec::resolveSchema));
  }

  protected OpenApiValidationFilter(OpenApi spec, JsonMapper mapper, Validator validator) {
    this.specification = spec;
    this.mapper = mapper;
    this.validator = validator;
    this.operationsByPath = initializeOperationsMap();
    logSupportedOperations();
  }

  @Override
  public String description() {
    return "OpenAPI filter";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    String method = exchange.getRequestMethod();
    String path = specification.stripBasePath(exchange.getRequestURI().getPath());
    Operation operation = findOperation(method, path);

    if (!validateHeaders(exchange, operation)
        || !validateQueryParameters(exchange, operation)
        || !validateRequestBody(exchange, operation)) {
      respondAsBadRequest(exchange);
      return;
    }

    exchange.setAttribute("operation-id", operation.operationId());
    chain.doFilter(exchange);
  }

  private Map<String, Operation> initializeOperationsMap() {
    var operations = new ConcurrentHashMap<String, Operation>();
    for (Entry<String, PathItem> pathEntry : specification.paths().entrySet()) {
      String path = specification.stripBasePath(pathEntry.getKey());
      PathItem item = pathEntry.getValue();

      addOperation(operations, "HEAD", path, item.head());
      addOperation(operations, "GET", path, item.get());
      addOperation(operations, "PUT", path, item.put());
      addOperation(operations, "POST", path, item.post());
      addOperation(operations, "DELETE", path, item.delete());
      addOperation(operations, "CONNECT", path, item.connect());
      addOperation(operations, "OPTIONS", path, item.options());
      addOperation(operations, "TRACE", path, item.trace());
      addOperation(operations, "PATCH", path, item.patch());
    }
    return operations;
  }

  private void addOperation(Map<String, Operation> ops, String method, String path, Operation o) {
    Optional.ofNullable(o).ifPresent(operation -> ops.put(method + ":" + path, operation));
  }

  private void logSupportedOperations() {
    operationsByPath.forEach(
        (verb, operation) -> {
          var id = operation.operationId();
          LOG.debug("Server supports {} via operation-id '{}'", verb, id);
        });
  }

  protected String extractPath(String input) {
    return input.split("^[A-Z]+:")[1];
  }

  private Operation findOperation(String method, String path) {
    String key = method + ":" + path;
    Operation operation = operationsByPath.get(key);

    if (isNull(operation)) {
      operation = findOperationWithPathParameters(method, path);
    }

    return Optional.ofNullable(operation)
        .orElseThrow(() -> new OperationIdNotFoundException(method, path));
  }

  private Operation findOperationWithPathParameters(String method, String path) {
    return operationsByPath.entrySet().stream()
        .filter(entry -> isMatchingPathOperation(entry, path))
        .map(Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private boolean isMatchingPathOperation(Entry<String, Operation> entry, String path) {
    String unresolvedPath = extractPath(entry.getKey());
    Operation operation = entry.getValue();
    return operation.hasPathParameters()
        && operation.matchesPath(unresolvedPath, path, validator::validate);
  }

  private boolean validateHeaders(HttpExchange exchange, Operation operation) {
    if (!operation.hasHeaderParameters()) {
      return true;
    }

    Headers headers = exchange.getRequestHeaders();
    var headerParameters =
        resolveParameters(operation).stream().filter(Parameter::isHeader).toList();
    for (Parameter parameter : headerParameters) {
      if (!parameter.isHeader()) {
        continue;
      }

      var headerValues = Optional.ofNullable(headers.get(parameter.name())).orElseGet(List::of);
      if (isInvalidHeader(parameter, headerValues)) {
        return false;
      }
    }
    return true;
  }

  private boolean validateQueryParameters(HttpExchange exchange, Operation operation) {
    if (!operation.hasQueryParameters()) {
      return true;
    }

    String query = exchange.getRequestURI().getQuery();
    if (isNull(query) || query.isBlank()) {
      return false;
    }

    Map<String, String> queryPairs = parseQueryString(query);

    var queryParameters = resolveParameters(operation).stream().filter(Parameter::isQuery).toList();
    for (Parameter queryParameter : queryParameters) {
      String paramName = queryParameter.name();
      String paramValue = queryPairs.get(paramName);

      if (isInvalidQueryParameter(queryParameter, paramValue)) {
        return false;
      }
    }

    return true;
  }

  private boolean validateRequestBody(HttpExchange exchange, Operation operation)
      throws IOException {

    byte[] requestBody = getRequestBody(exchange);
    if (isEmpty(requestBody)) {
      return true;
    }

    LOG.debug("Validating request body...");

    String contentType = exchange.getRequestHeaders().getFirst("content-type");
    MediaType mediaType = operation.requestBody().content().get(contentType);
    Schema schema = resolveSchema(mediaType.schema());

    var mappedBody = mapper.mapFrom(requestBody);

    return validator.validate(mappedBody, schema);
  }

  private boolean isEmpty(byte[] data) {
    return data == null || data.length == 0;
  }

  private Schema resolveSchema(Schema schema) {
    return nonNull(schema.$ref()) ? specification.resolveSchema(schema.$ref()) : schema;
  }

  private List<Parameter> resolveParameters(Operation operation) {
    return operation.parameters().stream().map(this::resolveParameterReference).toList();
  }

  private Parameter resolveParameterReference(Parameter parameter) {
    return nonNull(parameter.$ref()) ? specification.resolveParameter(parameter.$ref()) : parameter;
  }

  private boolean isInvalidHeader(Parameter parameter, List<String> headerValues) {
    if (parameter.required() && headerValues.isEmpty()) {
      return true;
    }

    return headerValues.stream().anyMatch(header -> !isValidHeaderValue(header, parameter));
  }

  private boolean isValidHeaderValue(String header, Parameter parameter) {
    LOG.debug("Validating '{}' against parameter '{}'", header, parameter.name());
    return validator.validate(header, parameter.schema());
  }

  private Map<String, String> parseQueryString(String query) {
    return Arrays.stream(query.split("&"))
        .filter(pair -> pair.contains("="))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> pair[0], pair -> pair[1], (existing, replacement) -> existing));
  }

  private boolean isInvalidQueryParameter(Parameter parameter, String value) {
    if (parameter.required() && (isNull(value) || value.isEmpty())) {
      LOG.debug("Required query parameter '{}' not found", parameter.name());
      return true;
    }

    if (nonNull(value)) {
      LOG.debug(
          "Validating query parameter value '{}' against parameter '{}'", value, parameter.name());
      return !validator.validate(value, parameter.schema());
    }

    return true;
  }

  private void respondAsBadRequest(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(HTTP_BAD_REQUEST, 0);
    }
  }
}
