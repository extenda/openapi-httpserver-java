package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PrimitiveSchemasTest {
  @Test
  void stringSchemaCarriesAllStringFields() {
    StringSchema s =
        new StringSchema(
            Set.of(TypeName.STRING), "^x.*$", 1, 64, "uuid", List.of("a", "b"), Map.of());
    assertThat(s.pattern()).isEqualTo("^x.*$");
    assertThat(s.minLength()).isEqualTo(1);
    assertThat(s.maxLength()).isEqualTo(64);
    assertThat(s.format()).isEqualTo("uuid");
    assertThat(s.enumValues()).containsExactly("a", "b");
  }

  @Test
  void numberSchemaCarriesAllNumericConstraints() {
    NumberSchema n =
        new NumberSchema(Set.of(TypeName.NUMBER), 0, 100, null, 100, 5, "double", Map.of());
    assertThat(n.minimum().intValue()).isZero();
    assertThat(n.maximum()).isEqualTo(100);
    assertThat(n.exclusiveMaximum()).isEqualTo(100);
    assertThat(n.multipleOf()).isEqualTo(5);
  }

  @Test
  void integerSchemaUsesLongConstraints() {
    IntegerSchema i =
        new IntegerSchema(
            Set.of(TypeName.INTEGER), 1L, 2_000_000_000L, null, null, null, "int64", Map.of());
    assertThat(i.maximum()).isEqualTo(2_000_000_000L);
    assertThat(i.format()).isEqualTo("int64");
  }

  @Test
  void nullSchemaTypesIsAlwaysNull() {
    assertThat(new NullSchema(Map.of()).types()).containsExactly(TypeName.NULL);
  }

  @Test
  void refSchemaTypesIsEmpty() {
    RefSchema r = new RefSchema("#/components/schemas/User", Map.of());
    assertThat(r.pointer()).isEqualTo("#/components/schemas/User");
    assertThat(r.types()).isEmpty();
  }
}
