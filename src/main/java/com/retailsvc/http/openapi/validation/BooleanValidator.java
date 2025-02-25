package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.Schema;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BooleanValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public boolean validate(Object input, Schema schema) {
    if (!schema.isBoolean()) {
      return false;
    }

    if (!(input instanceof Boolean bool)) {
      return false;
    }

    LOG.debug("Validated as boolean? {}", bool);
    return true;
  }
}
