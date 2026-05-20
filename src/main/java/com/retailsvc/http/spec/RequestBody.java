package com.retailsvc.http.spec;

import java.util.Map;

/**
 * OpenAPI {@code requestBody} object describing the payload an operation accepts.
 *
 * @param required whether the request body must be present
 * @param content supported media types keyed by content-type string
 */
public record RequestBody(boolean required, Map<String, MediaType> content) {}
