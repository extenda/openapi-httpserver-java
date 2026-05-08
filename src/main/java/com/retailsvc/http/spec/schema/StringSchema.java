package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Set;

public record StringSchema(
    Set<TypeName> types,
    String pattern,
    Integer minLength,
    Integer maxLength,
    String format,
    List<String> enumValues)
    implements Schema {}
