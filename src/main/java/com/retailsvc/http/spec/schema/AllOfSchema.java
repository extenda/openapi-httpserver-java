package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record AllOfSchema(List<Schema> parts) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
