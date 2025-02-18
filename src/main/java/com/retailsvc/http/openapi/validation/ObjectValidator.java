package com.retailsvc.http.openapi.validation;

import static java.util.function.Predicate.not;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final Validator rootValidator;
  private final Function<String, Schema> referencedSchema;

  public ObjectValidator(Validator rootValidator, Function<String, Schema> referencedSchema) {
    this.rootValidator = rootValidator;
    this.referencedSchema = referencedSchema;
  }

  private static boolean requiredFieldsMissing(Map<String, Object> input, List<String> required) {
    if (input.keySet().containsAll(required)) {
      return false;
    }
    input.keySet().stream()
        .filter(not(required::contains))
        .forEach(key -> LOG.warn("Required property '{}' not found.", key));
    return true;
  }

  @Override
  public boolean validate(Object input, Schema schema) {
    if (!schema.isObject()) {
      return false;
    }

    Map<String, Object> json = (Map<String, Object>) input;

    LOG.debug("Validate as object: {}", json);

    Map<String, Object> properties = schema.properties();
    Map<String, Object> objectProperties =
        (Map<String, Object>) properties.getOrDefault("properties", properties);
    List<String> required = Optional.ofNullable(schema.required()).orElseGet(List::of);

    // Verify that all required properties are present in the input, else fail
    if (requiredFieldsMissing(json, required)) {
      return false;
    }

    for (Entry<String, Object> entry : json.entrySet()) {
      if (!objectProperties.containsKey(entry.getKey())) {
        LOG.debug("No sub-schema found for {}, skipping validation.", entry.getKey());
        return true;
      }

      var subSchema = (Map<String, Object>) objectProperties.get(entry.getKey());
      var type = Optional.ofNullable(subSchema.get("type")).map(String::valueOf).orElse(null);
      var items = (Map<String, Object>) subSchema.get("items");
      var $ref =
          Optional.ofNullable(items)
              .map(i -> (String) items.get("$ref"))
              .orElseGet(() -> (String) subSchema.get("$ref"));
      var format = Optional.ofNullable(subSchema.get("format")).map(String::valueOf).orElse(null);
      var subRequired = (List<String>) subSchema.get("required");
      var max = getLimitForNumber(subSchema, "maximum", Double.MAX_VALUE);
      var min = getLimitForNumber(subSchema, "minimum", Double.MIN_VALUE);

      Schema schemaForProperty =
          Optional.ofNullable($ref)
              .map(referencedSchema)
              /*
               The reason for filtering;
               if type is 'array', the referenced schema is likely for a non-array type,
               instead create a new schema for 'array' type.
              */
              .filter(not(ignore -> "array".equals(type)))
              .orElseGet(
                  () -> new Schema($ref, type, format, subSchema, items, subRequired, max, min));

      Object propertyToValidate = entry.getValue();

      if (!rootValidator.validate(propertyToValidate, schemaForProperty)) {
        LOG.debug("Failed to validate '{}'", entry.getKey());
        return false;
      }
    }
    return true;
  }

  private static Number getLimitForNumber(Map<String, Object> props, String name, double limit) {
    return Optional.ofNullable(props).map(p -> p.get(name)).map(Number.class::cast).orElse(limit);
  }
}
