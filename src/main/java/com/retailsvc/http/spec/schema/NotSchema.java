package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code not}: the value must NOT match the inner schema.
 *
 * @param schema the schema that the value must fail to validate against
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record NotSchema(Schema schema, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
