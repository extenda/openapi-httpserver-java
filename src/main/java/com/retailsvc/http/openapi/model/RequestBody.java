package com.retailsvc.http.openapi.model;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNullElse;

import com.retailsvc.http.openapi.model.OpenApi.MediaType;
import java.util.List;
import java.util.Map;

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
    required = requireNonNullElse(required, emptyList());
  }
}
