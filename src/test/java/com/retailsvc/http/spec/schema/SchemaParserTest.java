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

  @Test
  void parsesObjectWithRequiredAndProperties() {
    Map<String, Object> raw =
        Map.of(
            "type", "object",
            "required", List.of("name"),
            "properties", Map.of("name", Map.of("type", "string")));
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.required()).containsExactly("name");
    assertThat(o.properties()).containsKey("name");
    assertThat(o.properties().get("name")).isInstanceOf(StringSchema.class);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void parsesObjectWithAdditionalPropertiesFalse() {
    Map<String, Object> raw = Map.of("type", "object", "additionalProperties", false);
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Forbidden.class);
  }

  @Test
  void parsesObjectWithAdditionalPropertiesSchema() {
    Map<String, Object> raw =
        Map.of("type", "object", "additionalProperties", Map.of("type", "string"));
    ObjectSchema o = (ObjectSchema) SchemaParser.parse(raw);
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.SchemaConstraint.class);
  }

  @Test
  void parsesArrayWithItems() {
    Map<String, Object> raw =
        Map.of(
            "type",
            "array",
            "items",
            Map.of("type", "integer"),
            "minItems",
            1,
            "uniqueItems",
            true);
    ArraySchema a = (ArraySchema) SchemaParser.parse(raw);
    assertThat(a.items()).isInstanceOf(IntegerSchema.class);
    assertThat(a.minItems()).isEqualTo(1);
    assertThat(a.uniqueItems()).isTrue();
  }

  @Test
  void parsesOneOf() {
    Map<String, Object> raw =
        Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer")));
    OneOfSchema o = (OneOfSchema) SchemaParser.parse(raw);
    assertThat(o.options()).hasSize(2);
    assertThat(o.options().get(0)).isInstanceOf(StringSchema.class);
  }

  @Test
  void parsesAnyOfAllOfNot() {
    assertThat(SchemaParser.parse(Map.of("anyOf", List.of(Map.of("type", "string")))))
        .isInstanceOf(AnyOfSchema.class);
    assertThat(SchemaParser.parse(Map.of("allOf", List.of(Map.of("type", "string")))))
        .isInstanceOf(AllOfSchema.class);
    assertThat(SchemaParser.parse(Map.of("not", Map.of("type", "null"))))
        .isInstanceOf(NotSchema.class);
  }

  @Test
  void parsesConst() {
    assertThat(SchemaParser.parse(Map.of("const", 42))).isInstanceOf(ConstSchema.class);
    assertThat(((ConstSchema) SchemaParser.parse(Map.of("const", "a"))).value()).isEqualTo("a");
  }

  @Test
  void parsesTopLevelEnumWithoutType() {
    Schema s = SchemaParser.parse(Map.of("enum", List.of(1, 2, 3)));
    assertThat(s).isInstanceOf(EnumSchema.class);
    assertThat(((EnumSchema) s).values()).containsExactly(1, 2, 3);
  }

  @Test
  void enumOnStringStaysAsStringSchema() {
    Schema s = SchemaParser.parse(Map.of("type", "string", "enum", List.of("a", "b")));
    assertThat(s).isInstanceOf(StringSchema.class);
    assertThat(((StringSchema) s).enumValues()).containsExactly("a", "b");
  }
}
