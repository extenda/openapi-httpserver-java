package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

public record BooleanSchema(Set<TypeName> types, Map<String, Object> extensions)
    implements Schema {}
