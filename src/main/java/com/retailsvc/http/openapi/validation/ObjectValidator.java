package com.retailsvc.http.openapi.validation;

import static java.util.function.Predicate.not;

import com.retailsvc.http.openapi.model.Schema;
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
  private static final String TYPE_ARRAY = "array";
  private static final String REF_KEY = "$ref";
  private static final String TYPE_KEY = "type";
  private static final String FORMAT_KEY = "format";
  private static final String PROPERTIES_KEY = "properties";

  private final Validator rootValidator;
  private final SchemaPropertyValidator propertyValidator;

  public ObjectValidator(Validator rootValidator, Function<String, Schema> referencedSchema) {
    this.rootValidator = rootValidator;
    this.propertyValidator = new SchemaPropertyValidator(referencedSchema);
  }

  @Override
  public boolean validate(Object input, Schema schema) {
    if (!schema.isObject()) {
      return false;
    }

    Map<String, Object> jsonObject = (Map<String, Object>) input;
    LOG.debug("Validate as object: {}", jsonObject);

    Map<String, Object> objectProperties = extractObjectProperties(schema);
    List<String> requiredFields = Optional.ofNullable(schema.required()).orElseGet(List::of);

    if (requiredFieldsMissing(jsonObject, requiredFields)) {
      return false;
    }

    return validateProperties(jsonObject, objectProperties);
  }

  private Map<String, Object> extractObjectProperties(Schema schema) {
    Map<String, Object> properties = schema.properties();
    return (Map<String, Object>) properties.getOrDefault(PROPERTIES_KEY, properties);
  }

  private boolean validateProperties(
      Map<String, Object> json, Map<String, Object> objectProperties) {
    for (Entry<String, Object> entry : json.entrySet()) {
      String propertyName = entry.getKey();
      if (!objectProperties.containsKey(propertyName)) {
        LOG.debug("No sub-schema found for {}, skipping validation.", propertyName);
        return true;
      }

      if (!validateProperty(
          entry.getValue(), (Map<String, Object>) objectProperties.get(propertyName))) {
        LOG.debug("Failed to validate '{}'", propertyName);
        return false;
      }
    }
    return true;
  }

  private boolean validateProperty(Object propertyValue, Map<String, Object> subSchema) {
    Schema propertySchema = propertyValidator.createPropertySchema(subSchema);
    return rootValidator.validate(propertyValue, propertySchema);
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

  record PropertyValidationContext(
      String type,
      String format,
      Map<String, Object> items,
      String ref,
      List<String> required,
      Number maximum,
      Number minimum) {}

  static class SchemaPropertyValidator {
    private final Function<String, Schema> referencedSchema;

    SchemaPropertyValidator(Function<String, Schema> referencedSchema) {
      this.referencedSchema = referencedSchema;
    }

    Schema createPropertySchema(Map<String, Object> subSchema) {
      PropertyValidationContext context = extractValidationContext(subSchema);
      return Optional.ofNullable(context.ref())
          .map(referencedSchema)
          .filter(not(ignore -> TYPE_ARRAY.equals(context.type())))
          .orElseGet(() -> createNewSchema(context, subSchema));
    }

    private PropertyValidationContext extractValidationContext(Map<String, Object> subSchema) {
      var type = Optional.ofNullable(subSchema.get(TYPE_KEY)).map(String::valueOf).orElse(null);
      var items = (Map<String, Object>) subSchema.get("items");
      var ref =
          Optional.ofNullable(items)
              .map(i -> (String) i.get(REF_KEY))
              .orElseGet(() -> (String) subSchema.get(REF_KEY));
      var format = Optional.ofNullable(subSchema.get(FORMAT_KEY)).map(String::valueOf).orElse(null);
      var required = (List<String>) subSchema.get("required");
      var max = getLimitForNumber(subSchema, "maximum", Double.MAX_VALUE);
      var min = getLimitForNumber(subSchema, "minimum", Double.MIN_VALUE);

      return new PropertyValidationContext(type, format, items, ref, required, max, min);
    }

    private Schema createNewSchema(
        PropertyValidationContext context, Map<String, Object> subSchema) {
      return new Schema(
          context.ref(),
          context.type(),
          context.format(),
          null,
          subSchema,
          context.items(),
          context.required(),
          context.maximum(),
          context.minimum());
    }

    private static Number getLimitForNumber(Map<String, Object> props, String name, double limit) {
      return Optional.ofNullable(props).map(p -> p.get(name)).map(Number.class::cast).orElse(limit);
    }
  }
}
