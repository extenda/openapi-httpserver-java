package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EnumSchema(List<Object> values, Map<String, Object> extensions) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
