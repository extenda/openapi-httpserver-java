package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code type: null}: only accepts the JSON {@code null} value.
 *
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record NullSchema(Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of(TypeName.NULL);
  }
}
