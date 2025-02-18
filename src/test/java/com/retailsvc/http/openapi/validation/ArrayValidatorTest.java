package com.retailsvc.http.openapi.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ArrayValidatorTest {

  private Validator rootValidator = mock();
  private Function<String, Schema> referencedSchema = mock();

  private final ArrayValidator validator = new ArrayValidator(rootValidator, referencedSchema);

  @Test
  void shouldReturnFalseWhenSchemaIsNotArray() {
    var schema = new Schema("string", null, Map.of(), Map.of(), List.of(), null, null);

    boolean result = validator.validate(List.of(), schema);

    assertThat(result).isFalse();
  }

  @Test
  void shouldReturnTrueForEmptyArray() {
    var schema =
        new Schema("array", null, Map.of(), Map.of("type", "string"), List.of(), null, null);

    boolean result = validator.validate(List.of(), schema);

    assertThat(result).isTrue();
  }

  @Test
  void validateShouldHandleNullPropertiesInSchema() {
    Map<String, Object> items = new HashMap<>();
    items.put("type", "number");
    items.put("properties", null);

    var schema = new Schema("array", null, Map.of(), items, List.of(), null, null);

    boolean result = validator.validate(List.of(), schema);

    assertThat(result).isTrue();
  }
}
