package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * A {@code $ref} to another schema. Resolved lazily by the validator.
 *
 * @param pointer the {@code $ref} pointer (e.g. {@code #/components/schemas/Foo})
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record RefSchema(String pointer, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
