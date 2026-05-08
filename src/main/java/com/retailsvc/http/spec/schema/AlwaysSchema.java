package com.retailsvc.http.spec.schema;

import java.util.Set;

public record AlwaysSchema() implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of();
  }
}
