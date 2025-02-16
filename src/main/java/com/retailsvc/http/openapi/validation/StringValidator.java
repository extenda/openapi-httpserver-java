package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

  @Override
  public boolean validate(Object input, Schema schema) {
    if (!schema.isString()) {
      return false;
    }
    if (!(input instanceof String json)) {
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

  private Pattern compile(String pattern) {
    LOG.debug("Compile and cache pattern: {}", pattern);
    return Pattern.compile(pattern);
  }
}
