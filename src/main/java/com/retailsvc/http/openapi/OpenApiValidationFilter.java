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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
  private final Map<String, Operation> operations;
  private final JsonMapper mapper;
  private final Validator validator;

  public OpenApiValidationFilter(OpenApi spec, JsonMapper mapper) {
    this(spec, mapper, new ValidatorImpl(spec::resolveSchema));
  }

  protected OpenApiValidationFilter(OpenApi spec, JsonMapper mapper, Validator validator) {
    this.mapper = mapper;
    if (LOG.isDebugEnabled()) {
      LOG.debug("Instantiating {}...", description());
    }

    this.specification = spec;
    this.operations = new ConcurrentHashMap<>();
    this.validator = validator;

    for (Entry<String, PathItem> pathItem : specification.paths().entrySet()) {
      String path = specification.stripBasePath(pathItem.getKey());
      PathItem item = pathItem.getValue();

      Optional.ofNullable(item.head())
          .ifPresent(operation -> operations.put("HEAD:" + path, operation));
      Optional.ofNullable(item.get())
          .ifPresent(operation -> operations.put("GET:" + path, operation));
      Optional.ofNullable(item.put())
          .ifPresent(operation -> operations.put("PUT:" + path, operation));
      Optional.ofNullable(item.post())
          .ifPresent(operation -> operations.put("POST:" + path, operation));
      Optional.ofNullable(item.delete())
          .ifPresent(operation -> operations.put("DELETE:" + path, operation));
      Optional.ofNullable(item.connect())
          .ifPresent(operation -> operations.put("CONNECT:" + path, operation));
      Optional.ofNullable(item.options())
          .ifPresent(operation -> operations.put("OPTIONS:" + path, operation));
      Optional.ofNullable(item.trace())
          .ifPresent(operation -> operations.put("TRACE:" + path, operation));
      Optional.ofNullable(item.patch())
          .ifPresent(operation -> operations.put("PATCH:" + path, operation));
    }

    operations.forEach(
        (verb, operation) -> {
          var id = operation.operationId();
          LOG.debug("Server supports {} via operation-id '{}'", verb, id);
        });
  }

  protected String cutPrefix(String input) {
    return input.split("^[A-Z]+:")[1];
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    String method = exchange.getRequestMethod();
    String path = specification.stripBasePath(exchange.getRequestURI().getPath());
    String key = method + ":" + path;

    Operation operation = operations.get(key);

    if (operation == null) {
      for (Entry<String, Operation> entry : operations.entrySet()) {
        String methodAndPath = entry.getKey();
        String unresolvedPath = cutPrefix(methodAndPath);
        Operation currentOperation = entry.getValue();

        if (currentOperation.hasPathParameters()
            && currentOperation.matchesPath(unresolvedPath, path, validator::validate)) {
          LOG.debug("Validated parameterized path '{} -> {}'", unresolvedPath, path);
          operation = currentOperation;
          break;
        }
      }
    }

    operation =
        Optional.ofNullable(operation)
            .orElseThrow(() -> new OperationIdNotFoundException(method, path));

    // Validate headers
    if (operation.hasHeaderParameters()) {
      Headers headers = exchange.getRequestHeaders();
      for (Parameter parameter : operation.parameters()) {
        if (nonNull(parameter.$ref())) {
          // parameter has ref, find it instead
          parameter = specification.resolveParameter(parameter.$ref());
        }

        if (!parameter.isHeader()) {
          continue;
        }

        List<String> headerValues =
            Optional.ofNullable(headers.get(parameter.name())).orElseGet(ArrayList::new);

        if (parameter.required() && headerValues.isEmpty()) {
          respondAsBadRequest(exchange);
          return;
        }

        for (String header : headerValues) {
          LOG.debug("Validating '{}' against parameter '{}'", header, parameter.name());

          if (!validator.validate(header, parameter.schema())) {
            respondAsBadRequest(exchange);
            return;
          }
        }
      }
    }

    // Validate query params
    if (operation.hasQueryParameters()) {
      String query = exchange.getRequestURI().getQuery();

      if (isNull(query) || query.isBlank()) {
        respondAsBadRequest(exchange);
        return;
      }

      for (Parameter queryParameter : operation.parameters()) {
        if (nonNull(queryParameter.$ref())) {
          // parameter has ref, find it instead
          queryParameter = specification.resolveParameter(queryParameter.$ref());
        }

        if (!queryParameter.isQuery()) {
          continue;
        }

        var required = queryParameter.required();
        var queryName = queryParameter.name();

        if (required && !query.contains(queryName)) {
          LOG.debug("Required query parameter '{}' not found in '{}'", queryName, query);
          respondAsBadRequest(exchange);
          return;
        }

        String[] queryPairs = query.split("&");
        for (int i = 0; i < queryPairs.length; i++) {
          String[] splitPair = queryPairs[i].split("=");
          String name = splitPair[0];
          String value = splitPair[1];

          if (queryParameter.name().equals(name)) {
            Schema schema = queryParameter.schema();
            LOG.debug("Validating query parameter value '{}' against parameter '{}'", value, name);
            boolean valid = validator.validate(value, schema);
            if (required && !valid) {
              respondAsBadRequest(exchange);
              return;
            }
            if (!valid) {
              respondAsBadRequest(exchange);
              return;
            }
            // optimization: Remove the validated pair for next iterations
            queryPairs[i] = "";
          }
        }
        // optimization
        query =
            String.join("&", queryPairs)
                // replace '&&' -> '&'
                .replaceAll("&{2,}", "&")
                // cut leading and trailing ampersand
                .replaceFirst("(^&)|(&$)", "");
      }
    }

    byte[] readBodyBytes = getRequestBody(exchange);
    if (readBodyBytes != null && readBodyBytes.length > 0) {
      var mappedBody = mapper.mapFrom(readBodyBytes);

      LOG.debug("Validating request body...");

      String contentType = exchange.getRequestHeaders().getFirst("content-type");
      MediaType mediaType = operation.requestBody().content().get(contentType);
      Schema schema = mediaType.schema();

      if (nonNull(schema.$ref())) {
        schema = specification.resolveSchema(schema.$ref());
      }

      if (!validator.validate(mappedBody, schema)) {
        respondAsBadRequest(exchange);
        return;
      }
    }

    exchange.setAttribute("operation-id", operation.operationId());

    chain.doFilter(exchange);
  }

  private void respondAsBadRequest(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(HTTP_BAD_REQUEST, 0);
    }
  }

  @Override
  public String description() {
    return "OpenAPI filter";
  }
}
