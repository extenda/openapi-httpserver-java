package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * The JSON Schema boolean schema {@code true} — accepts any value without constraint.
 *
 * <p>In JSON Schema (and OpenAPI 3.1), a schema may be expressed as the literal boolean {@code
 * true}, which is equivalent to an empty object schema {@code {}} and validates successfully
 * against every instance. This record models that form. Its counterpart is {@link NeverSchema} (the
 * boolean schema {@code false}), which rejects every instance.
 *
 * <p>Because no type constraint applies to an "always" schema, {@link #types()} returns an empty
 * set.
 *
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node, keyed by
 *     extension name (including the {@code x-} prefix) with their raw parsed values.
 */
public record AlwaysSchema(Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
