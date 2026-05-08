package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TypeNameTest {
  @Test
  void parsesAllSevenJsonSchemaTypes() {
    assertThat(TypeName.fromJsonSchema("string")).isEqualTo(TypeName.STRING);
    assertThat(TypeName.fromJsonSchema("number")).isEqualTo(TypeName.NUMBER);
    assertThat(TypeName.fromJsonSchema("integer")).isEqualTo(TypeName.INTEGER);
    assertThat(TypeName.fromJsonSchema("boolean")).isEqualTo(TypeName.BOOLEAN);
    assertThat(TypeName.fromJsonSchema("object")).isEqualTo(TypeName.OBJECT);
    assertThat(TypeName.fromJsonSchema("array")).isEqualTo(TypeName.ARRAY);
    assertThat(TypeName.fromJsonSchema("null")).isEqualTo(TypeName.NULL);
  }

  @Test
  void unknownTypeNameThrows() {
    assertThrows(IllegalArgumentException.class, () -> TypeName.fromJsonSchema("widget"));
  }
}
