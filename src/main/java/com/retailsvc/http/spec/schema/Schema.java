package com.retailsvc.http.spec.schema;

import java.util.Set;

public sealed interface Schema
    permits StringSchema,
        NumberSchema,
        IntegerSchema,
        BooleanSchema,
        ObjectSchema,
        ArraySchema,
        NullSchema,
        RefSchema {
  Set<TypeName> types();
}
