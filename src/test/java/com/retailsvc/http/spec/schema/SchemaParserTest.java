package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaParserTest {
  @Test
  void parsesString() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "minLength", 1, "maxLength", 64));
    assertThat(s).isInstanceOf(StringSchema.class);
    StringSchema str = (StringSchema) s;
    assertThat(str.minLength()).isEqualTo(1);
    assertThat(str.maxLength()).isEqualTo(64);
  }

  @Test
  void parsesIntegerWithFormat() {
    Schema s = SchemaParser.parse(Map.of("type", "integer", "format", "int64", "minimum", 0));
    assertThat(s).isInstanceOf(IntegerSchema.class);
    assertThat(((IntegerSchema) s).format()).isEqualTo("int64");
    assertThat(((IntegerSchema) s).minimum()).isEqualTo(0L);
  }

  @Test
  void parsesNumber() {
    Schema s = SchemaParser.parse(Map.of("type", "number", "multipleOf", 0.5));
    assertThat(s).isInstanceOf(NumberSchema.class);
    assertThat(((NumberSchema) s).multipleOf()).isEqualTo(0.5);
  }

  @Test
  void parsesBoolean() {
    assertThat(SchemaParser.parse(Map.of("type", "boolean"))).isInstanceOf(BooleanSchema.class);
  }

  @Test
  void parsesNull() {
    assertThat(SchemaParser.parse(Map.of("type", "null"))).isInstanceOf(NullSchema.class);
  }

  @Test
  void parsesRef() {
    Schema s = SchemaParser.parse(Map.of("$ref", "#/components/schemas/User"));
    assertThat(s).isInstanceOf(RefSchema.class);
    assertThat(((RefSchema) s).pointer()).isEqualTo("#/components/schemas/User");
  }

  @Test
  void parsesTypeArrayWithNullForNullable() {
    Schema s = SchemaParser.parse(Map.of("type", List.of("string", "null")));
    assertThat(s).isInstanceOf(StringSchema.class);
    assertThat(s.types()).containsExactlyInAnyOrder(TypeName.STRING, TypeName.NULL);
  }

  @Test
  void parsesLegacyNullableTrueAsTypeUnion() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "nullable", true));
    assertThat(s.types()).containsExactlyInAnyOrder(TypeName.STRING, TypeName.NULL);
  }
}
