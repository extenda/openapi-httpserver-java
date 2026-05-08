package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.NullSchema;
import com.retailsvc.http.spec.schema.OneOfSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultValidatorDispatchTest {
  private final Validator v =
      new DefaultValidator(
          name -> {
            throw new AssertionError("no refs");
          });

  @Test
  void nullSchemaAcceptsNull() {
    v.validate(null, new NullSchema(), "");
  }

  @Test
  void nullSchemaRejectsNonNull() {
    var schema = new NullSchema();
    assertThatThrownBy(() -> v.validate("x", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void booleanSchemaAcceptsBoolean() {
    v.validate(true, new BooleanSchema(Set.of(TypeName.BOOLEAN)), "/v");
  }

  @Test
  void booleanSchemaRejectsString() {
    var schema = new BooleanSchema(Set.of(TypeName.BOOLEAN));
    assertThatThrownBy(() -> v.validate("x", schema, "/v")).isInstanceOf(ValidationException.class);
  }

  @Test
  void combinatorThrowsUnsupported() {
    var schema = new OneOfSchema(List.of());
    assertThatThrownBy(() -> v.validate("x", schema, "/v"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
