package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NotSchema(Schema schema) implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
