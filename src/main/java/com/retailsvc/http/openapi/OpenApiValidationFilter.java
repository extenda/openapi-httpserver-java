package com.retailsvc.http.openapi;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;

import com.retailsvc.http.openapi.exceptions.OperationIdNotFoundException;
import com.retailsvc.http.openapi.model.GetRequestBody;
import com.retailsvc.http.openapi.model.OpenApi;
import com.retailsvc.http.openapi.model.OpenApi.MediaType;
import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.retailsvc.http.openapi.model.OpenApi.PathItem;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import com.retailsvc.http.openapi.model.RequestBodyMapper;
import com.retailsvc.http.openapi.validation.Validator;
import com.retailsvc.http.openapi.validation.ValidatorImpl;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
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
  private final RequestBodyMapper mapper;
  private final Validator validator;

  public OpenApiValidationFilter(OpenApi spec, RequestBodyMapper mapper) {
    this(spec, mapper, new ValidatorImpl());
  }

  protected OpenApiValidationFilter(OpenApi spec, RequestBodyMapper mapper, Validator validator) {
    this.mapper = mapper;
    LOG.debug("Instantiating {}...", description());

    this.specification = spec;
    this.operations = new ConcurrentHashMap<>();
    this.validator = validator;

    for (Entry<String, PathItem> pathItem : specification.paths().entrySet()) {
      String path = specification.stripBasePath(pathItem.getKey());
      PathItem item = pathItem.getValue();

      Optional.ofNullable(item.get())
          .ifPresent(operation -> operations.put("GET:" + path, operation));
      Optional.ofNullable(item.put())
          .ifPresent(operation -> operations.put("PUT:" + path, operation));
      Optional.ofNullable(item.post())
          .ifPresent(operation -> operations.put("POST:" + path, operation));
      Optional.ofNullable(item.delete())
          .ifPresent(operation -> operations.put("DELETE:" + path, operation));
    }

    LOG.debug("Operations: {}", operations);
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    String method = exchange.getRequestMethod();
    String requestURI = exchange.getRequestURI().getPath();
    String path = specification.stripBasePath(requestURI);
    String key = method + ":" + path;

    Operation operation =
        Optional.ofNullable(operations.get(key))
            .orElseThrow(() -> new OperationIdNotFoundException(method, path));

    byte[] readBodyBytes = getRequestBody(exchange);
    if (readBodyBytes != null && readBodyBytes.length > 0) {
      var mappedBody = mapper.mapFrom(readBodyBytes);

      String contentType = exchange.getRequestHeaders().getFirst("content-type");
      MediaType mediaType = operation.requestBody().content().get(contentType);
      Schema schema = mediaType.schema();

      boolean isValid = validator.validate(mappedBody, schema);

      LOG.debug("Overall validation is {}", isValid ? "VALID" : "INVALID");

      if (!isValid) {
        try (exchange) {
          exchange.sendResponseHeaders(HTTP_BAD_REQUEST, 0);
          return;
        }
      }
    }
    chain.doFilter(exchange);
  }

  @Override
  public String description() {
    return "OpenAPI filter";
  }
}
