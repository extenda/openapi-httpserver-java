package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * JSON Schema boolean {@code false}: accepts nothing. Pairs with {@link AlwaysSchema} (boolean
 * {@code true}).
 *
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record NeverSchema(Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
