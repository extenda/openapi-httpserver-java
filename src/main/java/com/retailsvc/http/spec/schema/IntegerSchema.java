package com.retailsvc.http.spec.schema;

import java.util.Set;

public record IntegerSchema(
    Set<TypeName> types,
    Long minimum,
    Long maximum,
    Long exclusiveMinimum,
    Long exclusiveMaximum,
    Long multipleOf,
    String format)
    implements Schema {}
