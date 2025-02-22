package com.retailsvc.http.openapi.validation;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests complex or "deep" objects that are harder to test with Validators in isolation */
class ValidatorImplTest {

  private final Function<String, Schema> referencedSchema = schemaName -> null;

  private final Validator validator = new ValidatorImpl(referencedSchema);

  @Test
  void shouldValidateNestedObjectProperty() {
    Map<String, Object> nestedSchema = Map.of("type", "string");
    Map<String, Object> properties = Map.of("properties", Map.of("name", nestedSchema));
    Schema schema =
        new Schema("object", null, null, properties, emptyMap(), emptyList(), null, null);
    Map<String, Object> input = Map.of("name", "John");

    boolean result = validator.validate(input, schema);

    assertThat(result).isTrue();
  }

  private static Stream<Arguments> complexObjectValidationArguments() {
    Map<String, Object> validPersonSchema =
        Map.of(
            "properties",
            Map.of(
                "name", Map.of("type", "string"),
                "age", Map.of("type", "number", "minimum", 0, "maximum", 100)));

    return Stream.of(
        arguments(Map.of("name", "John", "age", 25), validPersonSchema, true),
        arguments(Map.of("name", "John", "age", 200), validPersonSchema, false),
        arguments(Map.of("name", 123, "age", 25), validPersonSchema, false));
  }

  @ParameterizedTest
  @MethodSource("complexObjectValidationArguments")
  void shouldValidateComplexObjects(
      Map<String, Object> input, Map<String, Object> schemaProperties, boolean expected) {
    Schema schema =
        new Schema("object", null, null, schemaProperties, emptyMap(), emptyList(), null, null);

    boolean result = validator.validate(input, schema);

    assertThat(result).isEqualTo(expected);
  }

  @Test
  void validateShouldReturnFalseForInvalidArrayElements() {
    Map<String, Object> items = new HashMap<>();
    items.put("type", "string");
    Schema schema = new Schema("array", null, null, Map.of(), items, List.of(), null, null);

    boolean result = validator.validate(List.of("test", 123), schema);

    assertThat(result).isFalse();
  }

  @Test
  void validateShouldReturnTrueForValidStringArray() {
    Map<String, Object> items = new HashMap<>();
    items.put("type", "string");
    Schema schema = new Schema("array", null, null, Map.of(), items, List.of(), null, null);

    boolean result = validator.validate(List.of("test1", "test2"), schema);

    assertThat(result).isTrue();
  }
}
