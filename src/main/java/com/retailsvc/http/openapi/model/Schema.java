package com.retailsvc.http.openapi.model;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Objects.isNull;

import com.retailsvc.http.openapi.exceptions.LoadSpecificationException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The 'schema' allows the definition of input and output data types.
 *
 * @see <a href="https://swagger.io/specification/#schema-object">Schema Object</a>
 */
public record Schema(
    String $ref,
    String type,
    String format,
    String pattern,
    Map<String, Object> properties,
    Map<String, Object> items,
    List<String> required,
    Number maximum,
    Number minimum) {

  /**
   * If Schema has a $ref, we do not set any properties. The properties will be resolved later via
   * the referenced component {@link Components}.
   */
  public Schema {
    if (isNull($ref)) {
      if (type == null || type.isBlank()) {
        throw new LoadSpecificationException("Type is missing");
      }
      if (isNull(format) && isNumber()) {
        format = "int32";
      }
      required = Objects.requireNonNullElse(required, emptyList());
      items = Objects.requireNonNullElse(items, emptyMap());
      properties = Objects.requireNonNullElse(properties, emptyMap());
      maximum = Objects.requireNonNullElse(maximum, Double.MAX_VALUE);
      minimum = Objects.requireNonNullElse(minimum, Double.MIN_VALUE);
    }
  }

  public Schema(
      String type,
      String format,
      String pattern,
      Map<String, Object> properties,
      Map<String, Object> items,
      List<String> required,
      Number maximum,
      Number minimum) {
    this(null, type, format, pattern, properties, items, required, maximum, minimum);
  }

  public boolean isString() {
    return "string".equalsIgnoreCase(type);
  }

  public boolean isBoolean() {
    return "boolean".equalsIgnoreCase(type);
  }

  public boolean isInteger() {
    return isNumber() && Optional.ofNullable(format).map("int32"::equalsIgnoreCase).orElse(true);
  }

  public boolean isLong() {
    return isNumber() && Optional.ofNullable(format).map("int64"::equalsIgnoreCase).orElse(false);
  }

  public boolean isNumber() {
    return "number".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type);
  }

  public boolean isObject() {
    return "object".equalsIgnoreCase(type);
  }

  public boolean isArray() {
    return "array".equalsIgnoreCase(type);
  }
}
