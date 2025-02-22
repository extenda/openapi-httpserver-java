package com.retailsvc.http.openapi.model;

import com.retailsvc.http.openapi.model.OpenApi.Schema;

/**
 * Represents a supported 'media-type' for an endpoint.
 *
 * @param schema The schema defining the content of the request, response, or parameter
 * @see <a href="https://swagger.io/specification/#media-type-object">Media Type Object</a>
 */
public record MediaType(Schema schema) {}
