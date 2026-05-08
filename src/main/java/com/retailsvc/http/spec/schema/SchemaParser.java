package com.retailsvc.http.spec.schema;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaParser {
  private SchemaParser() {}

  @SuppressWarnings("unchecked")
  public static Schema parse(Map<String, Object> raw) {
    if (raw.containsKey("$ref")) {
      return new RefSchema((String) raw.get("$ref"));
    }

    Set<TypeName> types = parseTypes(raw);

    // Pick primary (non-null) type for record dispatch.
    TypeName primary =
        types.stream().filter(t -> t != TypeName.NULL).findFirst().orElse(TypeName.NULL);

    return switch (primary) {
      case STRING -> parseString(raw, types);
      case INTEGER -> parseInteger(raw, types);
      case NUMBER -> parseNumber(raw, types);
      case BOOLEAN -> new BooleanSchema(types);
      case NULL -> new NullSchema();
      case OBJECT, ARRAY ->
          throw new UnsupportedOperationException("object/array parsing comes in C2");
    };
  }

  private static Set<TypeName> parseTypes(Map<String, Object> raw) {
    Object t = raw.get("type");
    EnumSet<TypeName> out = EnumSet.noneOf(TypeName.class);
    if (t instanceof String s) {
      out.add(TypeName.fromJsonSchema(s));
    } else if (t instanceof List<?> list) {
      for (Object name : list) {
        out.add(TypeName.fromJsonSchema((String) name));
      }
    }
    if (Boolean.TRUE.equals(raw.get("nullable"))) {
      out.add(TypeName.NULL);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static StringSchema parseString(Map<String, Object> raw, Set<TypeName> types) {
    return new StringSchema(
        types,
        (String) raw.get("pattern"),
        toIntOrNull(raw.get("minLength")),
        toIntOrNull(raw.get("maxLength")),
        (String) raw.get("format"),
        (List<String>) raw.get("enum"));
  }

  private static IntegerSchema parseInteger(Map<String, Object> raw, Set<TypeName> types) {
    return new IntegerSchema(
        types,
        toLongOrNull(raw.get("minimum")),
        toLongOrNull(raw.get("maximum")),
        toLongOrNull(raw.get("exclusiveMinimum")),
        toLongOrNull(raw.get("exclusiveMaximum")),
        toLongOrNull(raw.get("multipleOf")),
        (String) raw.get("format"));
  }

  private static NumberSchema parseNumber(Map<String, Object> raw, Set<TypeName> types) {
    return new NumberSchema(
        types,
        (Number) raw.get("minimum"),
        (Number) raw.get("maximum"),
        (Number) raw.get("exclusiveMinimum"),
        (Number) raw.get("exclusiveMaximum"),
        (Number) raw.get("multipleOf"),
        (String) raw.get("format"));
  }

  private static Integer toIntOrNull(Object v) {
    return v == null ? null : ((Number) v).intValue();
  }

  private static Long toLongOrNull(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }
}
