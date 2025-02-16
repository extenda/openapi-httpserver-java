package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final Validator rootValidator;

  public ObjectValidator(Validator rootValidator) {
    this.rootValidator = rootValidator;
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

    if (!json.keySet().containsAll(required)) {
      Set<String> keys = json.keySet();
      for (String key : required) {
        if (!keys.contains(key)) {
          LOG.warn("Required property '{}' not found.", key);
        }
      }
      return false;
    }

    for (Entry<String, Object> entry : json.entrySet()) {
      if (!objectProperties.containsKey(entry.getKey())) {
        LOG.debug("No sub-schema found for {}, skipping validation.", entry.getKey());
        return true;
      }

      var subSchema = (Map<String, Object>) objectProperties.get(entry.getKey());
      var type = subSchema.get("type").toString();
      var items = (Map<String, Object>) subSchema.get("items");
      var format = Optional.ofNullable(subSchema.get("format")).map(String::valueOf).orElse(null);
      var subRequired = (List<String>) subSchema.get("required");
      var maximum =
          Optional.ofNullable(subSchema.get("maximum"))
              .map(Number.class::cast)
              .orElse(Double.MAX_VALUE);
      var minimum =
          Optional.ofNullable(subSchema.get("minimum"))
              .map(Number.class::cast)
              .orElse(Double.MIN_VALUE);
      var propertySchema =
          new Schema(type, format, subSchema, items, subRequired, maximum, minimum);
      Object property = entry.getValue();

      if (!rootValidator.validate(property, propertySchema)) {
        LOG.debug("Failed to validate '{}'", entry.getKey());
        return false;
      }
    }
    return true;
  }
}
