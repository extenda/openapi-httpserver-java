package com.retailsvc.http.internal;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.validate.ValidationError;

/** Coerces wire-format strings (parameters, form-field values) to the target schema type. */
public final class ValueCoercion {

  private ValueCoercion() {}

  /**
   * Coerces a wire-format string to the Java type implied by the target schema.
   *
   * @param raw the raw string value (e.g. a query parameter or form field)
   * @param schema the target schema
   * @param pointer JSON pointer used in validation errors
   * @return the coerced value ({@code Long}, {@code Double}, {@code Boolean}, or the raw string)
   * @throws ValidationException if the value cannot be coerced to the schema type
   */
  public static Object coerce(String raw, Schema schema, String pointer) {
    return switch (schema) {
      case IntegerSchema _ -> {
        try {
          yield Long.parseLong(raw);
        } catch (NumberFormatException _) {
          throw new ValidationException(
              new ValidationError(pointer, "type", "expected integer", raw));
        }
      }
      case NumberSchema _ -> {
        double d;
        try {
          d = Double.parseDouble(raw);
        } catch (NumberFormatException _) {
          throw new ValidationException(
              new ValidationError(pointer, "type", "expected number", raw));
        }
        if (!Double.isFinite(d)) {
          throw new ValidationException(
              new ValidationError(pointer, "type", "expected number", raw));
        }
        yield d;
      }
      case BooleanSchema _ -> {
        if ("true".equals(raw)) {
          yield Boolean.TRUE;
        }
        if ("false".equals(raw)) {
          yield Boolean.FALSE;
        }
        throw new ValidationException(
            new ValidationError(pointer, "type", "expected boolean", raw));
      }
      default -> raw;
    };
  }
}
