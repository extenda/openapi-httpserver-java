package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValidatorImpl implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(ValidatorImpl.class);
  private static final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

  private final NumberValidator numberValidator;

  public ValidatorImpl() {
    numberValidator = new NumberValidator();
  }

  @Override
  public boolean validate(Object json, Schema schema) {
    return switch (schema.type()) {
      case "string" -> validate((String) json, schema);
      case "integer", "number" -> numberValidator.validate(json, schema);
      case "boolean" -> validateBoolean((Boolean) json, schema);
      case "object" -> validate((Map<String, Object>) json, schema);
      default -> false;
    };
  }

  private boolean validate(String json, Schema schema) {
    if (!schema.isString()) {
      return false;
    }
    LOG.debug("Validating string input: {}", json);

    Map<String, Object> properties = schema.properties();

    if (properties.containsKey("pattern")) {
      String patternString = properties.get("pattern").toString();
      Pattern pattern = patterns.computeIfAbsent(patternString, this::compile);
      boolean match = pattern.matcher(json).matches();
      LOG.debug("{} matches pattern {}? {}", json, pattern, match);
      return match;
    }

    if (properties.containsKey("format")) {
      String formatString = properties.get("format").toString();
      if ("uuid".equalsIgnoreCase(formatString)) {
        try {
          UUID.fromString(json);
          LOG.debug("Validated as UUID? true");
          return true;
        } catch (IllegalArgumentException e) {
          return false;
        }
      }
    }

    if (properties.containsKey("enum")) {
      List<String> enums = (List<String>) properties.get("enum");
      for (String value : enums) {
        if (value.equals(json)) {
          LOG.debug("Validated as enum? true");
          return true;
        }
      }
      return false;
    }

    return false;
  }

  private boolean validate(Map<String, Object> json, Schema schema) {
    if (!schema.isObject()) {
      return false;
    }
    LOG.debug("Validate as object: {}", json);

    Map<String, Object> properties = schema.properties();

    for (Entry<String, Object> entry : json.entrySet()) {
      Map<String, Object> subSchema = (Map<String, Object>) properties.get(entry.getKey());
      String type = subSchema.get("type").toString();
      Schema propertySchema = new Schema(type, subSchema);
      Object property = entry.getValue();

      if (!validate(property, propertySchema)) {
        return false;
      }
    }

    return true;
  }

  private boolean validateBoolean(Boolean bool, Schema schema) {
    if (!schema.isBoolean()) {
      return false;
    }
    LOG.debug("Validate as boolean: {}", bool);
    try {
      Objects.requireNonNull(bool);
      LOG.debug("Validated as boolean? true");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private Pattern compile(String pattern) {
    LOG.debug("Compile and cache pattern: {}", pattern);
    return Pattern.compile(pattern);
  }
}
