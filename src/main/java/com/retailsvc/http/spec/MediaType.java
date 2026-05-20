package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;

/**
 * OpenAPI {@code mediaType} object binding a content type to a payload schema.
 *
 * @param schema schema describing the payload for this media type
 */
public record MediaType(Schema schema) {}
