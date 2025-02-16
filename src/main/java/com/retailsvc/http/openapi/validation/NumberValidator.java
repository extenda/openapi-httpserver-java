package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.exceptions.BadRequestException;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NumberValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(NumberValidator.class);

  @Override
  public boolean validate(Object input, Schema schema) {
    try {
      if (!schema.isNumber()) {
        return false;
      }
      if (!(input instanceof Number number)) {
        return false;
      }

      LOG.debug("Validating number input: {}", number);

      if (number.longValue() > schema.maximum().longValue()) {
        LOG.debug("Value {} is larger than maximum {}", number, schema.maximum());
        return false;
      }
      if (number.longValue() < schema.minimum().longValue()) {
        LOG.debug("Value {} is smaller than minimum {}", number, schema.maximum());
        return false;
      }

      if (schema.isInteger()) {
        double value = number.doubleValue();
        boolean valid = Double.valueOf(value).equals(Math.floor(value));
        LOG.debug("Validated as integer? {}", valid);
        return valid;
      }

      if (schema.isLong()) {
        number = number.longValue();
      }

      if ("int32".equals(schema.format())) {
        number = number.intValue();
      }

      return switch (number) {
        case Long l -> validateLong(l);
        case Double d -> validateDouble(d);
        case Float f -> validateFloat(f);
        default -> false;
      };
    } catch (ClassCastException e) {
      LOG.error("Wrong class type found for input {}", input, e);
      throw new BadRequestException();
    } catch (Exception e) {
      LOG.error("Could not validate number {}", input, e);
      return false;
    }
  }

  private static boolean validateLong(Long l) {
    if (l == null) {
      return false;
    }
    LOG.debug("Validated as long? true");
    return true;
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
