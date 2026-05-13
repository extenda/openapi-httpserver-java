package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValueCoercionTest {

  private final Schema intSchema = anIntegerSchema();
  private final Schema numSchema = aNumberSchema();
  private final Schema boolSchema = aBooleanSchema();
  private final Schema strSchema = aStringSchema();

  @Test
  void coercesIntegerString() {
    assertThat(ValueCoercion.coerce("42", intSchema, "/a")).isEqualTo(42L);
  }

  @Test
  void coercesNumberString() {
    assertThat(ValueCoercion.coerce("3.14", numSchema, "/a")).isEqualTo(3.14);
  }

  @Test
  void coercesBooleanTrue() {
    assertThat(ValueCoercion.coerce("true", boolSchema, "/a")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void coercesBooleanFalse() {
    assertThat(ValueCoercion.coerce("false", boolSchema, "/a")).isEqualTo(Boolean.FALSE);
  }

  @Test
  void leavesStringSchemaUntouched() {
    assertThat(ValueCoercion.coerce("hello", strSchema, "/a")).isEqualTo("hello");
  }

  @Test
  void integerCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("abc", intSchema, "/a"))
        .isInstanceOf(ValidationException.class)
        .extracting("error.pointer", "error.keyword")
        .containsExactly("/a", "type");
  }

  @Test
  void numberCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("not-a-number", numSchema, "/x"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void booleanCoercionFailureThrowsValidationException() {
    assertThatThrownBy(() -> ValueCoercion.coerce("yes", boolSchema, "/b"))
        .isInstanceOf(ValidationException.class);
  }

  private static IntegerSchema anIntegerSchema() {
    return new IntegerSchema(
        Set.of(TypeName.INTEGER), null, null, null, null, null, null, Map.of());
  }

  private static NumberSchema aNumberSchema() {
    return new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, null, Map.of());
  }

  private static BooleanSchema aBooleanSchema() {
    return new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of());
  }

  private static StringSchema aStringSchema() {
    return new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null, Map.of());
  }
}
