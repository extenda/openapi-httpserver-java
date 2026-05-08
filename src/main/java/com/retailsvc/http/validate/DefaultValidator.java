package com.retailsvc.http.validate;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.AllOfSchema;
import com.retailsvc.http.spec.schema.AnyOfSchema;
import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.ConstSchema;
import com.retailsvc.http.spec.schema.EnumSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NotSchema;
import com.retailsvc.http.spec.schema.NullSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.OneOfSchema;
import com.retailsvc.http.spec.schema.RefSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.Objects;
import java.util.function.Function;

public final class DefaultValidator implements Validator {
  private final Function<String, Schema> refResolver;

  public DefaultValidator(Function<String, Schema> refResolver) {
    this.refResolver = refResolver;
  }

  @Override
  public void validate(Object value, Schema schema, String pointer) {
    if (value == null && schema.types().contains(TypeName.NULL)) return;

    switch (schema) {
      case RefSchema r -> validate(value, refResolver.apply(r.pointer()), pointer);
      case BooleanSchema b -> validateBoolean(value, pointer);
      case NullSchema n -> require(value == null, pointer, "type", "expected null");
      case StringSchema s -> validateString(value, s, pointer);
      case IntegerSchema i -> validateInteger(value, i, pointer);
      case NumberSchema n -> validateNumber(value, n, pointer);
      case ObjectSchema o -> validateObject(value, o, pointer);
      case ArraySchema a -> validateArray(value, a, pointer);
      case EnumSchema e ->
          require(e.values().contains(value), pointer, "enum", "value not in enum");
      case ConstSchema c ->
          require(Objects.equals(c.value(), value), pointer, "const", "value does not equal const");
      case OneOfSchema o -> throw new UnsupportedOperationException("oneOf not yet supported");
      case AnyOfSchema a -> throw new UnsupportedOperationException("anyOf not yet supported");
      case AllOfSchema a -> throw new UnsupportedOperationException("allOf not yet supported");
      case NotSchema n -> throw new UnsupportedOperationException("not not yet supported");
    }
  }

  private void validateBoolean(Object value, String pointer) {
    require(value instanceof Boolean, pointer, "type", "expected boolean");
  }

  private void validateString(Object value, StringSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements string");
  }

  private void validateInteger(Object value, IntegerSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements integer");
  }

  private void validateNumber(Object value, NumberSchema s, String pointer) {
    throw new UnsupportedOperationException("E3 implements number");
  }

  private void validateObject(Object value, ObjectSchema s, String pointer) {
    throw new UnsupportedOperationException("E4 implements object");
  }

  private void validateArray(Object value, ArraySchema s, String pointer) {
    throw new UnsupportedOperationException("E4 implements array");
  }

  static void require(boolean condition, String pointer, String keyword, String message) {
    if (!condition) {
      throw new ValidationException(new ValidationError(pointer, keyword, message, null));
    }
  }
}
