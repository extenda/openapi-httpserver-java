package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * Models the JSON Schema {@code const} keyword: an instance is valid if and only if its value is
 * deeply equal to {@link #value()} (compared via {@link java.util.Objects#equals(Object, Object)}).
 *
 * <p>{@link #types()} returns an empty set because the type is implied by the const value itself.
 *
 * @param value the required value; may be {@code null} to require a JSON {@code null}
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node
 */
public record ConstSchema(Object value, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
