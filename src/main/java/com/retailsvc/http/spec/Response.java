package com.retailsvc.http.spec;

import java.util.Map;

/**
 * OpenAPI {@code response} object describing the payload an operation returns for a given status.
 *
 * @param content supported media types keyed by content-type string
 */
public record Response(Map<String, MediaType> content) {}
