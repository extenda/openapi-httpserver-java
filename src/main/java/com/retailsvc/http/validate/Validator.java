package com.retailsvc.http.validate;

import com.retailsvc.http.spec.schema.Schema;

public interface Validator {
  /** Throws ValidationException on first failure. */
  void validate(Object value, Schema schema, String pointer);
}
