package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;

public class ValidatorImpl implements Validator {

  private final ArrayValidator arrayValidator;
  private final ObjectValidator objectValidator;
  private final StringValidator stringValidator;
  private final NumberValidator numberValidator;
  private final BooleanValidator booleanValidator;

  public ValidatorImpl() {
    arrayValidator = new ArrayValidator(this);
    objectValidator = new ObjectValidator(this);
    stringValidator = new StringValidator();
    numberValidator = new NumberValidator();
    booleanValidator = new BooleanValidator();
  }

  @Override
  public boolean validate(Object json, Schema schema) {
    return switch (schema.type()) {
      case "string" -> stringValidator.validate(json, schema);
      case "number" -> numberValidator.validate(json, schema);
      case "boolean" -> booleanValidator.validate(json, schema);
      case "object" -> objectValidator.validate(json, schema);
      case "array" -> arrayValidator.validate(json, schema);
      default -> false;
    };
  }
}
