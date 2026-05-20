package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code type: object}.
 *
 * @param types the declared types (may include {@link TypeName#NULL} for nullable)
 * @param properties declared property name to schema mapping
 * @param required names of required properties
 * @param additionalProperties policy for properties not declared in {@code properties}
 * @param minProperties minimum property count, or {@code null}
 * @param maxProperties maximum property count, or {@code null}
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record ObjectSchema(
    Set<TypeName> types,
    Map<String, Schema> properties,
    List<String> required,
    AdditionalProperties additionalProperties,
    Integer minProperties,
    Integer maxProperties,
    Map<String, Object> extensions)
    implements Schema {}
