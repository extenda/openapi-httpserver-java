package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NumberValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(NumberValidator.class);

  @Override
  public boolean validate(Object input, Schema schema) {
    if (!schema.isInteger() && !schema.isNumber()) {
      return false;
    }
    Number number = (Number) input;

    LOG.debug("Validating number input: {}", number);

    if (schema.isInteger()) {
      boolean valid = number.longValue() % number.doubleValue() == 0;
      LOG.debug("Validated as integer? {}", valid);
      return valid;
    }

    return switch (number) {
      case Long l -> validateLong(l);
      case Double d -> validateDouble(d);
      case Float f -> validateFloat(f);
      default -> {
        LOG.error("Could not validate number {}", number);
        yield false;
      }
    };
  }

  private static boolean validateLong(Long l) {
    boolean valid = l.doubleValue() % 1 == 0;
    LOG.debug("Validated as long? {}", valid);
    return valid;
  }

  private static boolean validateDouble(Double d) {
    boolean valid = !d.isNaN() && !d.isInfinite();
    LOG.debug("Validated as double? {}", valid);
    return valid;
  }

  private static boolean validateFloat(Float f) {
    boolean valid = !f.isNaN() && !f.isInfinite();
    LOG.debug("Validated as float? {}", valid);
    return valid;
  }
}
