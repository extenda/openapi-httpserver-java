package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema composition for the JSON Schema {@code allOf} keyword.
 *
 * <p>A value is valid against this schema if and only if it validates successfully against every
 * subschema in {@link #parts()}. The composition is conjunctive: each part contributes its own
 * constraints, and all must hold simultaneously.
 *
 * <p>{@link #types()} returns an empty set because {@code allOf} is itself type-agnostic — it does
 * not declare a JSON type. The validator descends into each part and lets the parts assert any type
 * constraints they carry.
 *
 * @param parts the subschemas that the value must all match
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node
 */
public record AllOfSchema(List<Schema> parts, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
