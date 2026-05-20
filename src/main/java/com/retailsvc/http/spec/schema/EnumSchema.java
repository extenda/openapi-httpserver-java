package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Models a JSON Schema {@code enum} constraint: an instance is valid only if it is deeply equal
 * (via {@link java.util.Objects#equals(Object, Object)}) to one of the allowed {@link #values()}.
 *
 * <p>{@link #types()} returns an empty set; the permitted type is implied by the enum values
 * themselves rather than declared via a {@code type} keyword.
 *
 * <p>Note: most string enums in OpenAPI are modelled as {@link StringSchema} with {@code
 * enumValues()} populated. This {@code EnumSchema} covers the case where {@code enum} appears
 * without an explicit {@code type} keyword.
 *
 * @param values the permitted values (any JSON-mappable Java type)
 * @param extensions OpenAPI {@code x-} extension keywords on this schema node
 */
public record EnumSchema(List<Object> values, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
