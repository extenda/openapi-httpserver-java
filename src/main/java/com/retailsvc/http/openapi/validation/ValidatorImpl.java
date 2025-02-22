package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.Schema;
import java.util.function.Function;

public class ValidatorImpl implements Validator {

  private final ArrayValidator arrayValidator;
  private final ObjectValidator objectValidator;
  private final StringValidator stringValidator;
  private final NumberValidator numberValidator;
  private final BooleanValidator booleanValidator;

  /**
   * Root validator delegating to child validators.
   *
   * @param referencedSchema Function to access referenced schemas ($refs) if referenced in any
   *     property. Complex properties, such as lists and objects can hold referenced components.
   */
  public ValidatorImpl(Function<String, Schema> referencedSchema) {
    arrayValidator = new ArrayValidator(this, referencedSchema);
    objectValidator = new ObjectValidator(this, referencedSchema);
    stringValidator = new StringValidator();
    numberValidator = new NumberValidator();
    booleanValidator = new BooleanValidator();
  }

  @Override
  public boolean validate(Object json, Schema schema) {
    return switch (schema.type()) {
      case "string" -> stringValidator.validate(json, schema);
      case "number", "integer" -> numberValidator.validate(json, schema);
      case "boolean" -> booleanValidator.validate(json, schema);
      case "object" -> objectValidator.validate(json, schema);
      case "array" -> arrayValidator.validate(json, schema);
      default -> false;
    };
  }
}
