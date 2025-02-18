package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArrayValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final Validator rootValidator;
  private final Function<String, Schema> referencedSchema;

  /**
   * Validate lists
   *
   * @param rootValidator The parent that delegates types to correct validator
   * @param referencedSchema Referenced schema registry
   */
  public ArrayValidator(Validator rootValidator, Function<String, Schema> referencedSchema) {
    this.rootValidator = rootValidator;
    this.referencedSchema = referencedSchema;
  }

  @Override
  public boolean validate(Object json, Schema schema) {
    if (!schema.isArray()) {
      return false;
    }

    Iterable<?> iterable = (Iterable<?>) json;

    LOG.debug("Validate as list: {}", iterable);

    Map<String, Object> items = schema.items();
    String type = (String) items.get("type");
    String $ref = (String) items.get("$ref");
    Map<String, Object> props = (Map<String, Object>) items.get("properties");
    String format = (String) items.get("format");
    List<String> required = (List<String>) items.get("required");
    var max = getLimitForNumber(props, "maximum", Double.MAX_VALUE);
    var min = getLimitForNumber(props, "minimum", Double.MIN_VALUE);

    for (Object entry : iterable) {
      Schema propertySchema =
          Optional.ofNullable($ref)
              .map(referencedSchema)
              .orElseGet(() -> new Schema($ref, type, format, props, items, required, max, min));

      if (!rootValidator.validate(entry, propertySchema)) {
        LOG.debug("Failed to validate '{}'", entry);
        return false;
      }
    }
    return true;
  }

  private static Number getLimitForNumber(Map<String, Object> props, String name, double limit) {
    return Optional.ofNullable(props).map(p -> p.get(name)).map(Number.class::cast).orElse(limit);
  }
}
