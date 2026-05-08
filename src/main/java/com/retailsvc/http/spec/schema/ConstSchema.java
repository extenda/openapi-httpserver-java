package com.retailsvc.http.spec.schema;

import java.util.Set;

public record ConstSchema(Object value) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
