package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
      case "array" -> validate((List<Object>) json, schema);
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
          LOG.debug("Failed to validate UUID.", e);
          return false;
        }
      }
      if ("date-time".equalsIgnoreCase(formatString)) {
        try {
          java.time.OffsetDateTime.parse(json);
          LOG.debug("Validated as date-time? true");
          return true;
        } catch (Exception e) {
          LOG.debug("Failed to validate date-time.", e);
          return false;
        }
      }
      if ("date".equalsIgnoreCase(formatString)) {
        try {
          java.time.LocalDate.parse(json);
          LOG.debug("Validated as date? true");
          return true;
        } catch (Exception e) {
          LOG.trace("Failed to validate date.", e);
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

    return true;
  }

  private boolean validate(Map<String, Object> json, Schema schema) {
    if (!schema.isObject()) {
      return false;
    }
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
              .orElse(Long.MAX_VALUE);
      var minimum =
          Optional.ofNullable(subSchema.get("minimum"))
              .map(Number.class::cast)
              .orElse(Long.MIN_VALUE);
      var propertySchema =
          new Schema(type, format, subSchema, items, subRequired, maximum, minimum);
      Object property = entry.getValue();

      if (!validate(property, propertySchema)) {
        LOG.debug("Failed to validate '{}'", entry.getKey());
        return false;
      }
    }
    return true;
  }

  private boolean validate(List<Object> json, Schema schema) {
    if (!schema.isArray()) {
      return false;
    }
    LOG.debug("Validate as list: {}", json);

    Map<String, Object> items = schema.items();
    String type = (String) items.get("type");
    Map<String, Object> props = (Map<String, Object>) items.get("properties");
    String format = (String) items.get("format");
    List<String> required = (List<String>) items.get("required");
    var maximum =
        Optional.ofNullable(props)
            .map(p -> p.get("maximum"))
            .map(Number.class::cast)
            .orElse(Long.MAX_VALUE);
    var minimum =
        Optional.ofNullable(props)
            .map(p -> p.get("minimum"))
            .map(Number.class::cast)
            .orElse(Long.MIN_VALUE);

    for (Object entry : json) {
      if (!validate(entry, new Schema(type, format, props, items, required, maximum, minimum))) {
        LOG.debug("Failed to validate '{}'", entry);
        return false;
      }
    }
    return true;
  }

  private boolean validateBoolean(Boolean bool, Schema schema) {
    if (!schema.isBoolean()) {
      return false;
    }

    LOG.debug("Validate as boolean: '{}'", bool);

    try {
      Objects.requireNonNull(bool);
      LOG.debug("Validated as boolean? true");
      return true;
    } catch (Exception e) {
      LOG.trace("Failed to validate boolean.", e);
      return false;
    }
  }

  private Pattern compile(String pattern) {
    LOG.debug("Compile and cache pattern: {}", pattern);
    return Pattern.compile(pattern);
  }
}
