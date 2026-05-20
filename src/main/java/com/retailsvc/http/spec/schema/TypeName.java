package com.retailsvc.http.spec.schema;

/** The seven standard JSON Schema primitive types. */
public enum TypeName {
  /** JSON string. */
  STRING,
  /** JSON number (any numeric value). */
  NUMBER,
  /** JSON integer (a number with no fractional part). */
  INTEGER,
  /** JSON boolean ({@code true} or {@code false}). */
  BOOLEAN,
  /** JSON object. */
  OBJECT,
  /** JSON array. */
  ARRAY,
  /** JSON null. */
  NULL;

  /**
   * Maps a JSON Schema {@code type} string to the matching {@link TypeName}.
   *
   * @param name lowercase JSON Schema type name (e.g. {@code "string"})
   * @return the corresponding constant
   * @throws IllegalArgumentException if {@code name} is not a known JSON Schema type
   */
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
