package com.retailsvc.http.openapi.validation;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObjectValidatorTest {

  private final Validator rootValidator = mock();
  private final Function<String, Schema> referencedSchema = mock();
  private final ObjectValidator validator = new ObjectValidator(rootValidator, referencedSchema);

  @Test
  void shouldReturnFalseWhenSchemaIsNotObject() {
    var schema = new Schema("string", null, null, emptyMap(), emptyMap(), emptyList(), null, null);
    Map<String, Object> input = Map.of("key", "value");
    boolean result = validator.validate(input, schema);
    assertThat(result).isFalse();
  }

  @Test
  void shouldReturnTrueWhenPropertyHasNoSubSchema() {
    Map<String, Object> properties = Map.of("properties", emptyMap());
    Schema schema =
        new Schema("object", null, null, properties, emptyMap(), emptyList(), null, null);
    Map<String, Object> input = Map.of("unknownProperty", "value");

    boolean result = validator.validate(input, schema);

    assertThat(result).isTrue();
  }

  @Test
  void shouldReturnFalseWhenNestedPropertyValidationFails() {
    Map<String, Object> nestedSchema =
        Map.of(
            "type", "number",
            "minimum", 0,
            "maximum", 100);
    Map<String, Object> properties = Map.of("properties", Map.of("age", nestedSchema));
    var schema = new Schema("object", null, null, properties, emptyMap(), emptyList(), null, null);
    Map<String, Object> input = Map.of("age", "invalid");

    boolean result = validator.validate(input, schema);

    assertThat(result).isFalse();
  }

  private static Stream<Arguments> requiredFieldsValidationArguments() {
    return Stream.of(
        arguments(Map.of("name", "John"), List.of("name"), true),
        arguments(Map.of("age", 25), List.of("name"), false),
        arguments(Map.of("name", "John", "age", 25), List.of("name", "age"), true),
        arguments(emptyMap(), List.of("name"), false));
  }

  @ParameterizedTest
  @MethodSource("requiredFieldsValidationArguments")
  void shouldValidateRequiredFields(
      Map<String, Object> input, List<String> required, boolean expected) {
    var schema = new Schema("object", null, null, emptyMap(), emptyMap(), required, null, null);
    boolean result = validator.validate(input, schema);
    assertThat(result).isEqualTo(expected);
  }
}
