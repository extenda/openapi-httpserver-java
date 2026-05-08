package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record AnyOfSchema(List<Schema> options) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
