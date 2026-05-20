package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * Parsed JSON Schema node with {@code type: array}.
 *
 * <p>Represents an array schema as defined by OpenAPI 3.1 / JSON Schema 2020-12. Instances are
 * produced by the spec parser and consumed by the validator to enforce array-shaped payloads:
 * element schema, cardinality bounds, and uniqueness.
 *
 * @param types the JSON Schema {@code type} set; typically the singleton {@code [ARRAY]}, or {@code
 *     [ARRAY, NULL]} when the schema is nullable
 * @param items the schema applied to every element of the array
 * @param minItems minimum number of elements; {@code null} means no lower bound
 * @param maxItems maximum number of elements; {@code null} means no upper bound
 * @param uniqueItems whether duplicate elements are rejected
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node
 */
public record ArraySchema(
    Set<TypeName> types,
    Schema items,
    Integer minItems,
    Integer maxItems,
    boolean uniqueItems,
    Map<String, Object> extensions)
    implements Schema {}
