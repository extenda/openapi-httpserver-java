package com.retailsvc.http.spec.schema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaParser {
  private SchemaParser() {}

  private static final String FORMAT_KEY = "format";

  @SuppressWarnings("unchecked")
  public static Schema parse(Map<String, Object> raw) {
    if (raw.containsKey("$ref")) {
      return new RefSchema((String) raw.get("$ref"));
    }

    if (raw.containsKey("oneOf")) {
      return new OneOfSchema(parseList(raw, "oneOf"));
    }
    if (raw.containsKey("anyOf")) {
      return new AnyOfSchema(parseList(raw, "anyOf"));
    }
    if (raw.containsKey("allOf")) {
      return new AllOfSchema(parseList(raw, "allOf"));
    }
    if (raw.containsKey("not")) {
      return new NotSchema(parse((Map<String, Object>) raw.get("not")));
    }
    if (raw.containsKey("const")) {
      return new ConstSchema(raw.get("const"));
    }
    if (raw.containsKey("enum") && !raw.containsKey("type")) {
      return new EnumSchema(List.copyOf((List<Object>) raw.get("enum")));
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
      case OBJECT -> parseObject(raw, types);
      case ARRAY -> parseArray(raw, types);
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
        (String) raw.get(FORMAT_KEY),
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
        (String) raw.get(FORMAT_KEY));
  }

  private static NumberSchema parseNumber(Map<String, Object> raw, Set<TypeName> types) {
    return new NumberSchema(
        types,
        (Number) raw.get("minimum"),
        (Number) raw.get("maximum"),
        (Number) raw.get("exclusiveMinimum"),
        (Number) raw.get("exclusiveMaximum"),
        (Number) raw.get("multipleOf"),
        (String) raw.get(FORMAT_KEY));
  }

  @SuppressWarnings("unchecked")
  private static ObjectSchema parseObject(Map<String, Object> raw, Set<TypeName> types) {
    Map<String, Object> rawProps = (Map<String, Object>) raw.getOrDefault("properties", Map.of());
    Map<String, Schema> properties = new LinkedHashMap<>();
    for (var e : rawProps.entrySet()) {
      properties.put(e.getKey(), parse((Map<String, Object>) e.getValue()));
    }
    List<String> required = (List<String>) raw.getOrDefault("required", List.of());
    AdditionalProperties ap = parseAdditionalProperties(raw.get("additionalProperties"));
    return new ObjectSchema(
        types,
        Map.copyOf(properties),
        List.copyOf(required),
        ap,
        toIntOrNull(raw.get("minProperties")),
        toIntOrNull(raw.get("maxProperties")));
  }

  @SuppressWarnings("unchecked")
  private static AdditionalProperties parseAdditionalProperties(Object value) {
    return switch (value) {
      case null -> new AdditionalProperties.Allowed();
      case Boolean b when b -> new AdditionalProperties.Allowed();
      case Boolean _ -> new AdditionalProperties.Forbidden();
      default -> new AdditionalProperties.SchemaConstraint(parse((Map<String, Object>) value));
    };
  }

  @SuppressWarnings("unchecked")
  private static ArraySchema parseArray(Map<String, Object> raw, Set<TypeName> types) {
    Map<String, Object> items = (Map<String, Object>) raw.getOrDefault("items", Map.of());
    Schema itemSchema = items.isEmpty() ? new NullSchema() : parse(items);
    return new ArraySchema(
        types,
        itemSchema,
        toIntOrNull(raw.get("minItems")),
        toIntOrNull(raw.get("maxItems")),
        Boolean.TRUE.equals(raw.get("uniqueItems")));
  }

  @SuppressWarnings("unchecked")
  private static List<Schema> parseList(Map<String, Object> raw, String key) {
    List<Map<String, Object>> raws = (List<Map<String, Object>>) raw.get(key);
    List<Schema> out = new ArrayList<>(raws.size());
    for (Map<String, Object> r : raws) {
      out.add(parse(r));
    }
    return List.copyOf(out);
  }

  private static Integer toIntOrNull(Object v) {
    return v == null ? null : ((Number) v).intValue();
  }

  private static Long toLongOrNull(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }
}
