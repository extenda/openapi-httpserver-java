package com.retailsvc.http.spec.schema;

import java.util.Set;

public record BooleanSchema(Set<TypeName> types) implements Schema {}
