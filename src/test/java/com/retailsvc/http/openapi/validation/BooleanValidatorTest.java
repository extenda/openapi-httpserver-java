package com.retailsvc.http.openapi.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BooleanValidatorTest {

  private final BooleanValidator validator = new BooleanValidator();
  private Schema booleanSchema;
  private Schema nonBooleanSchema;

  @BeforeEach
  void setUp() {
    booleanSchema = new Schema("boolean", null, null, null, null, null, null);
    nonBooleanSchema = new Schema("string", null, null, null, null, null, null);
  }

  @Test
  void shouldValidateTrueValue() {
    boolean result = validator.validate(Boolean.TRUE, booleanSchema);
    assertThat(result).isTrue();
  }

  @Test
  void shouldValidateFalseValue() {
    boolean result = validator.validate(Boolean.FALSE, booleanSchema);
    assertThat(result).isTrue();
  }

  @Test
  void shouldRejectNonBooleanSchema() {
    boolean result = validator.validate(Boolean.TRUE, nonBooleanSchema);
    assertThat(result).isFalse();
  }

  @Test
  void shouldRejectNullInput() {
    boolean result = validator.validate(null, booleanSchema);
    assertThat(result).isFalse();
  }

  @Test
  void shouldRejectNonBooleanInput() {
    boolean result = validator.validate("true", booleanSchema);
    assertThat(result).isFalse();
  }
}
