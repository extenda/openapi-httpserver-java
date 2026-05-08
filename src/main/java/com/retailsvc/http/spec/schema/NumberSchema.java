package com.retailsvc.http.spec.schema;

import java.util.Set;

public record NumberSchema(
    Set<TypeName> types,
    Number minimum,
    Number maximum,
    Number exclusiveMinimum,
    Number exclusiveMaximum,
    Number multipleOf,
    String format)
    implements Schema {}
