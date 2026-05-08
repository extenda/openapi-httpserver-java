package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ObjectSchema(
    Set<TypeName> types,
    Map<String, Schema> properties,
    List<String> required,
    AdditionalProperties additionalProperties,
    Integer minProperties,
    Integer maxProperties)
    implements Schema {}
