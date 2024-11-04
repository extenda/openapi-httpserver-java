package com.retailsvc.http.openapi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NumberValidatorTest {

  NumberValidator numberValidator = new NumberValidator();

  @Test
  void shouldReturnFalseForNonNumberSchema() {
    Schema schema = mock(Schema.class);
    when(schema.isInteger()).thenReturn(false);
    when(schema.isNumber()).thenReturn(false);

    boolean isValid = numberValidator.validate(123, schema);

    assertThat(isValid).isFalse();
  }

  @Test
  void shouldValidateIntegerSchemaTrueForIntegerInput() {
    Schema schema = mock(Schema.class);
    when(schema.isInteger()).thenReturn(true);
    when(schema.isNumber()).thenReturn(false);

    boolean isValid = numberValidator.validate(123, schema);

    assertThat(isValid).isTrue();
  }

  @Test
  void shouldValidateIntegerSchemaFalseForNonIntegerInput() {
    Schema schema = mock(Schema.class);
    when(schema.isInteger()).thenReturn(true);
    when(schema.isNumber()).thenReturn(false);

    boolean isValid = numberValidator.validate(123.45, schema);

    assertThat(isValid).isFalse();
  }

  @Test
  void shouldValidateNumberSchemaTrueForValidNumberInput() {
    Schema schema = Mockito.mock(Schema.class);
    Mockito.when(schema.isInteger()).thenReturn(false);
    Mockito.when(schema.isNumber()).thenReturn(true);

    boolean isValidLong = numberValidator.validate(123L, schema);
    boolean isValidDouble = numberValidator.validate(123.45, schema);
    boolean isValidFloat = numberValidator.validate(123.45f, schema);

    assertThat(isValidLong).isTrue();
    assertThat(isValidDouble).isTrue();
    assertThat(isValidFloat).isTrue();
  }
}
