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

  static Map<String, Object> extractExtensions(Map<String, Object> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      if (e.getKey().startsWith("x-")) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return Map.copyOf(out);
  }

  public static Schema parse(Object raw) {
    if (raw instanceof Boolean b) {
      return b ? new AlwaysSchema(Map.of()) : new NeverSchema(Map.of());
    }
    if (raw instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return parseMap(typed);
    }
    throw new IllegalArgumentException("schema must be a boolean or an object, was: " + raw);
  }

  @SuppressWarnings("unchecked")
  private static Schema parseMap(Map<String, Object> raw) {
    if (raw.containsKey("$ref")) {
      return new RefSchema((String) raw.get("$ref"), extractExtensions(raw));
    }

    List<Schema> assertions = new ArrayList<>();

    Schema base = parseBaseIfPresent(raw);
    if (base != null) {
      assertions.add(base);
    }

    if (raw.containsKey("allOf")) {
      assertions.addAll(parseList(raw, "allOf"));
    }
    if (raw.containsKey("anyOf")) {
      assertions.add(new AnyOfSchema(parseList(raw, "anyOf"), extractExtensions(raw)));
    }
    if (raw.containsKey("oneOf")) {
      assertions.add(new OneOfSchema(parseList(raw, "oneOf"), extractExtensions(raw)));
    }
    if (raw.containsKey("not")) {
      assertions.add(new NotSchema(parse(raw.get("not")), extractExtensions(raw)));
    }

    return switch (assertions.size()) {
      case 0 -> permissiveObject(extractExtensions(raw));
      case 1 -> assertions.getFirst();
      default -> new AllOfSchema(List.copyOf(assertions), extractExtensions(raw));
    };
  }

  @SuppressWarnings("unchecked")
  private static Schema parseBaseIfPresent(Map<String, Object> raw) {
    if (raw.containsKey("const")) {
      return new ConstSchema(raw.get("const"), extractExtensions(raw));
    }
    if (raw.containsKey("enum") && !raw.containsKey("type")) {
      return new EnumSchema(List.copyOf((List<Object>) raw.get("enum")), extractExtensions(raw));
    }

    Set<TypeName> types = parseTypes(raw);
    if (types.isEmpty() && !hasObjectShapeKeywords(raw) && !hasArrayShapeKeywords(raw)) {
      return null;
    }
    if (types.isEmpty() && hasObjectShapeKeywords(raw)) {
      return parseObject(raw, types);
    }
    if (types.isEmpty() && hasArrayShapeKeywords(raw)) {
      return parseArray(raw, types);
    }

    TypeName primary =
        types.stream().filter(t -> t != TypeName.NULL).findFirst().orElse(TypeName.NULL);
    return switch (primary) {
      case STRING -> parseString(raw, types);
      case INTEGER -> parseInteger(raw, types);
      case NUMBER -> parseNumber(raw, types);
      case BOOLEAN -> new BooleanSchema(types, extractExtensions(raw));
      case NULL -> new NullSchema(extractExtensions(raw));
      case OBJECT -> parseObject(raw, types);
      case ARRAY -> parseArray(raw, types);
    };
  }

  private static boolean hasObjectShapeKeywords(Map<String, Object> raw) {
    return raw.containsKey("properties")
        || raw.containsKey("required")
        || raw.containsKey("additionalProperties")
        || raw.containsKey("minProperties")
        || raw.containsKey("maxProperties");
  }

  private static boolean hasArrayShapeKeywords(Map<String, Object> raw) {
    return raw.containsKey("items")
        || raw.containsKey("minItems")
        || raw.containsKey("maxItems")
        || raw.containsKey("uniqueItems");
  }

  private static Schema permissiveObject(Map<String, Object> extensions) {
    return new ObjectSchema(
        Set.of(), Map.of(), List.of(), new AdditionalProperties.Allowed(), null, null, extensions);
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
        (List<String>) raw.get("enum"),
        extractExtensions(raw));
  }

  private static IntegerSchema parseInteger(Map<String, Object> raw, Set<TypeName> types) {
    return new IntegerSchema(
        types,
        toLongOrNull(raw.get("minimum")),
        toLongOrNull(raw.get("maximum")),
        toLongOrNull(raw.get("exclusiveMinimum")),
        toLongOrNull(raw.get("exclusiveMaximum")),
        toLongOrNull(raw.get("multipleOf")),
        (String) raw.get(FORMAT_KEY),
        extractExtensions(raw));
  }

  private static NumberSchema parseNumber(Map<String, Object> raw, Set<TypeName> types) {
    return new NumberSchema(
        types,
        (Number) raw.get("minimum"),
        (Number) raw.get("maximum"),
        (Number) raw.get("exclusiveMinimum"),
        (Number) raw.get("exclusiveMaximum"),
        (Number) raw.get("multipleOf"),
        (String) raw.get(FORMAT_KEY),
        extractExtensions(raw));
  }

  @SuppressWarnings("unchecked")
  private static ObjectSchema parseObject(Map<String, Object> raw, Set<TypeName> types) {
    Map<String, Object> rawProps = (Map<String, Object>) raw.getOrDefault("properties", Map.of());
    Map<String, Schema> properties = new LinkedHashMap<>();
    for (var e : rawProps.entrySet()) {
      properties.put(e.getKey(), parse(e.getValue()));
    }
    List<String> required = (List<String>) raw.getOrDefault("required", List.of());
    AdditionalProperties ap = parseAdditionalProperties(raw.get("additionalProperties"));
    return new ObjectSchema(
        types,
        Map.copyOf(properties),
        List.copyOf(required),
        ap,
        toIntOrNull(raw.get("minProperties")),
        toIntOrNull(raw.get("maxProperties")),
        extractExtensions(raw));
  }

  @SuppressWarnings("unchecked")
  private static AdditionalProperties parseAdditionalProperties(Object value) {
    return switch (value) {
      case null -> new AdditionalProperties.Allowed();
      case Boolean b when b -> new AdditionalProperties.Allowed();
      case Boolean _ -> new AdditionalProperties.Forbidden();
      default -> new AdditionalProperties.SchemaConstraint(parse(value));
    };
  }

  @SuppressWarnings("unchecked")
  private static ArraySchema parseArray(Map<String, Object> raw, Set<TypeName> types) {
    Object itemsRaw = raw.get("items");
    Schema itemSchema;
    if (itemsRaw == null) {
      itemSchema = new NullSchema(Map.of());
    } else if (itemsRaw instanceof Boolean b) {
      itemSchema = b ? new AlwaysSchema(Map.of()) : new NeverSchema(Map.of());
    } else {
      Map<String, Object> items = (Map<String, Object>) itemsRaw;
      itemSchema = items.isEmpty() ? new NullSchema(Map.of()) : parse(items);
    }
    return new ArraySchema(
        types,
        itemSchema,
        toIntOrNull(raw.get("minItems")),
        toIntOrNull(raw.get("maxItems")),
        Boolean.TRUE.equals(raw.get("uniqueItems")),
        extractExtensions(raw));
  }

  @SuppressWarnings("unchecked")
  private static List<Schema> parseList(Map<String, Object> raw, String key) {
    List<Object> raws = (List<Object>) raw.get(key);
    List<Schema> out = new ArrayList<>(raws.size());
    for (Object r : raws) {
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
