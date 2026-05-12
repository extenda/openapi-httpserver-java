package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public record AlwaysSchema(Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
