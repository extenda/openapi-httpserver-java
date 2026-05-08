package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArrayValidationTest {
  private final Validator v =
      new DefaultValidator(
          name -> {
            throw new AssertionError();
          });

  private ArraySchema arr(Schema item, Integer minI, Integer maxI, boolean unique) {
    return new ArraySchema(Set.of(TypeName.ARRAY), item, minI, maxI, unique);
  }

  @Test
  void itemsValidated() {
    var s =
        arr(
            new IntegerSchema(Set.of(TypeName.INTEGER), 0L, 100L, null, null, null, "int32"),
            null,
            null,
            false);
    assertThatCode(() -> v.validate(List.of(1, 2, 3), s, "")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(List.of(1, -1), s, ""))
        .extracting(t -> ((ValidationException) t).error().pointer())
        .isEqualTo("/1");
  }

  @Test
  void minItemsEnforced() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), 2, null, false);
    assertThatThrownBy(() -> v.validate(List.of(true), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("minItems");
  }

  @Test
  void maxItemsEnforced() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), null, 1, false);
    assertThatThrownBy(() -> v.validate(List.of(true, false), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("maxItems");
  }

  @Test
  void uniqueItemsEnforced() {
    var s =
        arr(
            new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32"),
            null,
            null,
            true);
    assertThatThrownBy(() -> v.validate(List.of(1, 2, 1), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("uniqueItems");
  }

  @Test
  void rejectsNonIterable() {
    var s = arr(new BooleanSchema(Set.of(TypeName.BOOLEAN)), null, null, false);
    assertThatThrownBy(() -> v.validate("nope", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }
}
