package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public record NumberSchema(
    Set<TypeName> types,
    Number minimum,
    Number maximum,
    Number exclusiveMinimum,
    Number exclusiveMaximum,
    Number multipleOf,
    String format,
    Map<String, Object> extensions)
    implements Schema {}
