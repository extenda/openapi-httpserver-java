package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.Schema;

@FunctionalInterface
public interface Validator {

  /**
   * Take a json object as input. Validates according to the schema provided.
   *
   * @return True if valid according to schema, false otherwise
   */
  boolean validate(Object input, Schema schema);
}
