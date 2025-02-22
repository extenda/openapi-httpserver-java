package com.retailsvc.http.openapi.model;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNullElse;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the 'operation' for a method type.
 *
 * @param operationId the id used to map a handler to this endpoint.
 * @param requestBody the request body.
 * @param parameters the request parameters; headers, query- and path-parameters.
 * @param responses The available responses that can be returned.
 * @see <a href="https://swagger.io/specification/#operation-object">Operation Object</a>
 */
public record Operation(
    String operationId, RequestBody requestBody, List<Parameter> parameters, Object responses) {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String OPERATION_ID = "operation-id";

  private static final String HEADER = "header";
  private static final String QUERY = "query";
  private static final String PATH = "path";

  // Matches {.*} and is used to find tokens in paths
  private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([^}]+?)}");

  public Operation {
    parameters = requireNonNullElse(parameters, emptyList());
  }

  public boolean matchesPath(
      String schemaPath, String requestPath, BiPredicate<Object, Schema> validator) {
    if (schemaPath.equals(requestPath)) {
      return true;
    }

    if (!hasPathParameters()) {
      return false;
    }

    String[] splitSchemaPath = schemaPath.split("/");
    String[] splitRequestPath = requestPath.split("/");

    if (splitSchemaPath.length != splitRequestPath.length) {
      return false;
    }

    var foundParameters = new HashMap<String, String>();
    for (int i = 0; i < splitSchemaPath.length; i++) {
      String schemaToken = splitSchemaPath[i];
      String requestToken = splitRequestPath[i];

      // Extract named parameters using regex
      var matcher = TOKEN_PATTERN.matcher(schemaToken);
      while (matcher.find()) {
        foundParameters.put(matcher.group(1), requestToken);
      }
    }

    if (foundParameters.isEmpty()) {
      return false;
    }

    for (Parameter parameter : parameters()) {
      if (!parameter.isPath()) {
        continue;
      }
      var toValidate = foundParameters.get(parameter.name());
      var schema = parameter.schema();
      LOG.debug(
          "Validating path parameter value '{}' against path parameter '{}'",
          toValidate,
          parameter.name());
      if (!validator.test(toValidate, schema)) {
        LOG.debug("Failed to validate path parameter '{}'", parameter.name());
        return false;
      }
    }
    return true;
  }

  public boolean hasHeaderParameters() {
    return has(HEADER);
  }

  public boolean hasQueryParameters() {
    return has(QUERY);
  }

  public boolean hasPathParameters() {
    return has(PATH);
  }

  private boolean has(String identifier) {
    return parameters.stream().anyMatch(p -> identifier.equalsIgnoreCase(p.in()));
  }
}
