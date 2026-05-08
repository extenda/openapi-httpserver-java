package com.retailsvc.http.spec.schema;

import java.util.Set;

public sealed interface Schema permits BooleanSchema {
  Set<TypeName> types();
}
