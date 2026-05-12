package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public record ArraySchema(
    Set<TypeName> types,
    Schema items,
    Integer minItems,
    Integer maxItems,
    boolean uniqueItems,
    Map<String, Object> extensions)
    implements Schema {}
