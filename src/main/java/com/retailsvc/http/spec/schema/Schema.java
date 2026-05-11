package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public sealed interface Schema
    permits StringSchema,
        NumberSchema,
        IntegerSchema,
        BooleanSchema,
        ObjectSchema,
        ArraySchema,
        NullSchema,
        RefSchema,
        OneOfSchema,
        AnyOfSchema,
        AllOfSchema,
        NotSchema,
        ConstSchema,
        EnumSchema,
        AlwaysSchema,
        NeverSchema {
  Set<TypeName> types();

  Map<String, Object> extensions();
}
