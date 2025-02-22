package com.retailsvc.http.openapi.validation;

import com.retailsvc.http.openapi.model.Schema;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArrayValidator implements Validator {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String TYPE_KEY = "type";
  private static final String REF_KEY = "$ref";
  private static final String PROPERTIES_KEY = "properties";
  private static final String FORMAT_KEY = "format";
  private static final String REQUIRED_KEY = "required";
  private static final String MAXIMUM_KEY = "maximum";
  private static final String MINIMUM_KEY = "minimum";

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

    if (!(json instanceof Iterable<?> iterable)) {
      LOG.debug("Input is not an array -> {}", json);
      return false;
    }

    LOG.debug("Validate as list: {}", iterable);
    return validateIterableElements(iterable, extractItemProperties(schema));
  }

  private boolean validateIterableElements(Iterable<?> iterable, SchemaProperties props) {
    Schema propertySchema = createPropertySchema(props);

    for (Object entry : iterable) {
      if (!rootValidator.validate(entry, propertySchema)) {
        LOG.debug("Failed to validate '{}'", entry);
        return false;
      }
    }
    return true;
  }

  private SchemaProperties extractItemProperties(Schema schema) {
    Map<String, Object> items = schema.items();
    Map<String, Object> props = (Map<String, Object>) items.get(PROPERTIES_KEY);

    return new SchemaProperties(
        (String) items.get(REF_KEY),
        (String) items.get(TYPE_KEY),
        (String) items.get(FORMAT_KEY),
        props,
        items,
        (List<String>) items.get(REQUIRED_KEY),
        getLimitForNumber(props, MAXIMUM_KEY, Double.MAX_VALUE),
        getLimitForNumber(props, MINIMUM_KEY, Double.MIN_VALUE));
  }

  private Schema createPropertySchema(SchemaProperties props) {
    return Optional.ofNullable(props.ref())
        .map(referencedSchema)
        .orElseGet(
            () ->
                new Schema(
                    props.ref(),
                    props.type(),
                    props.format(),
                    null,
                    props.properties(),
                    props.items(),
                    props.required(),
                    props.maximum(),
                    props.minimum()));
  }

  private static Number getLimitForNumber(Map<String, Object> props, String name, double limit) {
    return Optional.ofNullable(props).map(p -> p.get(name)).map(Number.class::cast).orElse(limit);
  }

  private record SchemaProperties(
      String ref,
      String type,
      String format,
      Map<String, Object> properties,
      Map<String, Object> items,
      List<String> required,
      Number maximum,
      Number minimum) {}
}
