package com.retailsvc.http.spec.schema;

public enum TypeName {
  STRING,
  NUMBER,
  INTEGER,
  BOOLEAN,
  OBJECT,
  ARRAY,
  NULL;

  public static TypeName fromJsonSchema(String name) {
    return switch (name) {
      case "string" -> STRING;
      case "number" -> NUMBER;
      case "integer" -> INTEGER;
      case "boolean" -> BOOLEAN;
      case "object" -> OBJECT;
      case "array" -> ARRAY;
      case "null" -> NULL;
      default -> throw new IllegalArgumentException("unknown JSON Schema type: " + name);
    };
  }
}
