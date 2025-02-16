package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArrayValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final Validator rootValidator;

  public ArrayValidator(Validator rootValidator) {
    this.rootValidator = rootValidator;
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
    Map<String, Object> props = (Map<String, Object>) items.get("properties");
    String format = (String) items.get("format");
    List<String> required = (List<String>) items.get("required");
    var maximum =
        Optional.ofNullable(props)
            .map(p -> p.get("maximum"))
            .map(Number.class::cast)
            .orElse(Double.MAX_VALUE);
    var minimum =
        Optional.ofNullable(props)
            .map(p -> p.get("minimum"))
            .map(Number.class::cast)
            .orElse(Double.MIN_VALUE);

    for (Object entry : iterable) {
      if (!rootValidator.validate(
          entry, new Schema(type, format, props, items, required, maximum, minimum))) {
        LOG.debug("Failed to validate '{}'", entry);
        return false;
      }
    }
    return true;
  }
}
