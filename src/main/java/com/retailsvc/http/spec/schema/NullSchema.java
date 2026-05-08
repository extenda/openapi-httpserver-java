package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NullSchema() implements Schema {
  @Override
  public Set<TypeName> types() {
    return Set.of(TypeName.NULL);
  }
}
