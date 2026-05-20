package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Models the JSON Schema {@code anyOf} composition keyword: a value is valid when it validates
 * against at least one of the listed option subschemas.
 *
 * <p>Contrast with the sibling composition keywords:
 *
 * <ul>
 *   <li>{@link OneOfSchema} — the value must validate against exactly one option.
 *   <li>{@link AllOfSchema} — the value must validate against every option.
 * </ul>
 *
 * <p>{@link #types()} returns an empty set because an {@code anyOf} node does not itself constrain
 * a JSON type; the validator descends into each option and lets the matching branch determine the
 * effective type.
 *
 * @param options the candidate subschemas; at least one must match for the value to be valid
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node
 */
public record AnyOfSchema(List<Schema> options, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
