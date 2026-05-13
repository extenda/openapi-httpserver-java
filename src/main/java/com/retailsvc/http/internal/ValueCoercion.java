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
