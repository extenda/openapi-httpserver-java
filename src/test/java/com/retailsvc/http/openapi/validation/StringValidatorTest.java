package com.retailsvc.http.openapi.validation;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class StringValidatorTest {

  private final StringValidator validator = new StringValidator();

  @Test
  void shouldReturnFalseWhenSchemaIsNotString() {
    var schema = new Schema("number", null, null, emptyMap(), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate("test", schema);
    assertThat(result).isFalse();
  }

  @Test
  void shouldReturnTrueWhenNoValidationRulesExist() {
    var schema = new Schema("string", null, null, emptyMap(), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate("any string", schema);
    assertThat(result).isTrue();
  }

  @Test
  void shouldInvalidateIncorrectUUID() {
    var schema =
        new Schema(
            "string", null, null, Map.of("format", "uuid"), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate("not-a-uuid", schema);
    assertThat(result).isFalse();
  }

  static Stream<Arguments> patternTestCases() {
    return Stream.of(
        arguments("abc123", "^[a-z0-9]+$", true),
        arguments("ABC", "^[a-z0-9]+$", false),
        arguments("12345", "\\d+", true),
        arguments("abc", "\\d+", false));
  }

  @ParameterizedTest
  @MethodSource("patternTestCases")
  void shouldValidatePatterns(String input, String pattern, boolean expected) {
    var schema =
        new Schema(
            "string", null, null, Map.of("pattern", pattern), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate(input, schema);
    assertThat(result).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"123e4567-e89b-12d3-a456-426614174000", "87e3a40c-6def-4444-8888-7777b4e43b68"})
  void shouldValidateValidUUIDs(String input) {
    var schema =
        new Schema(
            "string", null, null, Map.of("format", "uuid"), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate(input, schema);
    assertThat(result).isTrue();
  }

  static Stream<Arguments> dateTimeTestCases() {
    return Stream.of(
        arguments("2025-02-16T15:30:00Z", "date-time", true),
        arguments("2025-02-16", "date", true),
        arguments("invalid-datetime", "date-time", false),
        arguments("invalid-date", "date", false));
  }

  @ParameterizedTest
  @MethodSource("dateTimeTestCases")
  void shouldValidateDateTimes(String input, String format, boolean expected) {
    var schema =
        new Schema(
            "string", null, null, Map.of("format", format), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate(input, schema);
    assertThat(result).isEqualTo(expected);
  }

  static Stream<Arguments> enumTestCases() {
    return Stream.of(
        arguments("RED", List.of("RED", "GREEN", "BLUE"), true),
        arguments("YELLOW", List.of("RED", "GREEN", "BLUE"), false),
        arguments("", List.of("RED", "GREEN", "BLUE"), false));
  }

  @ParameterizedTest
  @MethodSource("enumTestCases")
  void shouldValidateEnums(String input, List<String> enumValues, boolean expected) {
    var schema =
        new Schema(
            "string", null, null, Map.of("enum", enumValues), emptyMap(), emptyList(), null, null);
    boolean result = validator.validate(input, schema);
    assertThat(result).isEqualTo(expected);
  }
}
