package com.retailsvc.http.openapi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.exceptions.BadRequestException;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class NumberValidatorTest {

  private final NumberValidator numberValidator = new NumberValidator();

  private Schema schema;

  @BeforeEach
  void setUp() {
    schema = mock();
    when(schema.isInteger()).thenReturn(true);
    when(schema.isNumber()).thenReturn(true);
    when(schema.maximum()).thenReturn(Double.MAX_VALUE);
    when(schema.minimum()).thenReturn(Double.MIN_VALUE);
  }

  @Test
  void shouldReturnFalseForNonNumberSchema() {
    when(schema.isInteger()).thenReturn(false);
    when(schema.isNumber()).thenReturn(false);

    boolean isValid = numberValidator.validate(123, schema);

    assertThat(isValid).isFalse();
  }

  @Test
  void shouldValidateIntegerSchemaFalseForNonIntegerInput() {
    when(schema.isNumber()).thenReturn(false);

    boolean isValid = numberValidator.validate(123.45, schema);

    assertThat(isValid).isFalse();
  }

  @Test
  void shouldReturnFalseWhenValidatingObjectThatIsNotANumber() {
    boolean isValid = numberValidator.validate("test", schema);

    assertThat(isValid).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void shouldValidateNumberWithinBoundaries(int value) {
    when(schema.minimum()).thenReturn(1);
    when(schema.maximum()).thenReturn(5);

    boolean isValid = numberValidator.validate(value, schema);

    assertThat(isValid).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 6})
  void shouldValidateNumberOutsideOfBoundaries(int value) {
    when(schema.minimum()).thenReturn(1);
    when(schema.maximum()).thenReturn(5);

    boolean isValid = numberValidator.validate(value, schema);

    assertThat(isValid).isFalse();
  }

  static Stream<Arguments> numberTypes() {
    return Stream.of(arguments(1L), arguments(1), arguments(1.0), arguments(1F));
  }

  @ParameterizedTest
  @MethodSource("numberTypes")
  void shouldValidateAllNumberTypes(Number value) {
    when(schema.isInteger()).thenReturn(value instanceof Integer);
    when(schema.isLong()).thenReturn(value instanceof Long);

    boolean isValid = numberValidator.validate(value, schema);

    assertThat(isValid).isTrue();
  }

  @Test
  void testThatErrorCausesValidationToReturnFalse() {
    when(schema.isInteger()).thenThrow(new RuntimeException("test"));

    assertThat(numberValidator.validate(1, schema)).isFalse();
  }

  @Test
  void shouldReturnBadRequestWhenClassCastingFails() {
    when(schema.isInteger()).thenThrow(new ClassCastException("test"));

    assertThatException()
        .isThrownBy(() -> numberValidator.validate(1, schema))
        .isInstanceOf(BadRequestException.class);
  }
}
