package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code oneOf}: exactly one branch must match.
 *
 * @param options the candidate schemas
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record OneOfSchema(List<Schema> options, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
