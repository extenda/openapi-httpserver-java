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
    assertThat(((IntegerSchema) s).minimum().longValue()).isZero();
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
    // allOf with a single branch flattens to the branch itself (no wrapper AllOfSchema).
    assertThat(SchemaParser.parse(Map.of("allOf", List.of(Map.of("type", "string")))))
        .isInstanceOf(StringSchema.class);
    // allOf with multiple branches flattens into AllOfSchema.
    assertThat(
            SchemaParser.parse(
                Map.of(
                    "allOf",
                    List.of(Map.of("type", "string"), Map.of("type", "string", "minLength", 1)))))
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

  @Test
  void allOfWithSiblingTypeWrapsInImplicitAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of(
                "type", "object",
                "required", List.of("x"),
                "allOf", List.of(Map.of("type", "object", "required", List.of("y")))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(ObjectSchema.class);
    assertThat(((ObjectSchema) all.parts().get(0)).required()).containsExactly("x");
    assertThat(all.parts().get(1)).isInstanceOf(ObjectSchema.class);
    assertThat(((ObjectSchema) all.parts().get(1)).required()).containsExactly("y");
  }

  @Test
  void anyOfWithSiblingTypeWrapsInImplicitAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of(
                "type",
                "string",
                "anyOf",
                List.of(
                    Map.of("type", "string", "minLength", 1),
                    Map.of("type", "string", "maxLength", 10))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(AnyOfSchema.class);
    assertThat(((AnyOfSchema) all.parts().get(1)).options()).hasSize(2);
  }

  @Test
  void oneOfWithSiblingTypeWrapsInImplicitAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of(
                "type",
                "string",
                "oneOf",
                List.of(
                    Map.of("type", "string", "minLength", 1),
                    Map.of("type", "string", "maxLength", 10))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(OneOfSchema.class);
  }

  @Test
  void notWithSiblingTypeWrapsInImplicitAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of("type", "string", "not", Map.of("type", "string", "maxLength", 2)));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(NotSchema.class);
  }

  @Test
  void multipleCombinatorsInOneSchemaWrapInAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of(
                "anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer")),
                "not", Map.of("type", "boolean")));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(AnyOfSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(NotSchema.class);
  }

  @Test
  void parsesEmptySchemaAsPermissiveObject() {
    // {} emits no type assertion (permissive ObjectSchema); JSON Schema 3.1 default.
    // Behaviour change from the previous parser, which returned NullSchema.
    Schema s = SchemaParser.parse(Map.of());
    assertThat(s).isInstanceOf(ObjectSchema.class);
    ObjectSchema obj = (ObjectSchema) s;
    assertThat(obj.types()).isEmpty();
    assertThat(obj.properties()).isEmpty();
    assertThat(obj.required()).isEmpty();
    assertThat(obj.additionalProperties()).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void parsesEmptyAllOfAsPermissiveObject() {
    // allOf: [] contributes zero assertions; with no base assertion, the parser
    // falls back to permissive object.
    Schema s = SchemaParser.parse(Map.of("allOf", List.of()));
    assertThat(s).isInstanceOf(ObjectSchema.class);
  }

  @Test
  void allOfBranchesFlattenIntoOuterAllOf() {
    Schema s =
        SchemaParser.parse(
            Map.of(
                "type",
                "string",
                "allOf",
                List.of(
                    Map.of("type", "string", "minLength", 1),
                    Map.of("type", "string", "maxLength", 10))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    // Base + the two allOf branches flattened.
    assertThat(all.parts()).hasSize(3);
    assertThat(all.parts().get(0)).isInstanceOf(StringSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(StringSchema.class);
    assertThat(all.parts().get(2)).isInstanceOf(StringSchema.class);
    assertThat(((StringSchema) all.parts().get(1)).minLength()).isEqualTo(1);
    assertThat(((StringSchema) all.parts().get(2)).maxLength()).isEqualTo(10);
  }

  @Test
  void aloneCombinatorStillReturnsCombinatorRecord() {
    // Regression: when no base assertions are present, the result is still the
    // single combinator record, not an AllOfSchema with a single child.
    Schema s =
        SchemaParser.parse(
            Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))));
    assertThat(s).isInstanceOf(OneOfSchema.class);
    assertThat(((OneOfSchema) s).options()).hasSize(2);
  }

  @Test
  void refWithSiblingsIsParsedSolo() {
    // Deliberate limitation: $ref returns immediately and ignores sibling keywords.
    // JSON Schema 2020-12 allows $ref + siblings but that interaction is a separate gap.
    Schema s =
        SchemaParser.parse(
            Map.of(
                "$ref", "#/components/schemas/Foo",
                "type", "string",
                "minLength", 5));
    assertThat(s).isInstanceOf(RefSchema.class);
    assertThat(((RefSchema) s).pointer()).isEqualTo("#/components/schemas/Foo");
  }

  @Test
  void nestedAllOfIsNotFlattenedWhenBasePresent() {
    // Flattening is one-level: when a base assertion plus an outer allOf coexist,
    // the outer allOf branches are pulled into the assertions list, but any
    // allOf nested inside one of those branches stays wrapped.
    Schema s =
        SchemaParser.parse(
            Map.of(
                "type",
                "object",
                "allOf",
                List.of(
                    Map.of(
                        "allOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(ObjectSchema.class);
    assertThat(all.parts().get(1)).isInstanceOf(AllOfSchema.class);
    assertThat(((AllOfSchema) all.parts().get(1)).parts()).hasSize(2);
  }

  @Test
  void constWithSiblingAllOfWrapsInImplicitAllOf() {
    // const acts as a base assertion, so a sibling combinator wraps both in AllOf.
    Schema s =
        SchemaParser.parse(
            Map.of("const", 5, "allOf", List.of(Map.of("type", "integer", "minimum", 0))));
    assertThat(s).isInstanceOf(AllOfSchema.class);
    AllOfSchema all = (AllOfSchema) s;
    assertThat(all.parts()).hasSize(2);
    assertThat(all.parts().get(0)).isInstanceOf(ConstSchema.class);
    assertThat(((ConstSchema) all.parts().get(0)).value()).isEqualTo(5);
    assertThat(all.parts().get(1)).isInstanceOf(IntegerSchema.class);
  }

  @Test
  void notWithEmptyInnerSchemaWrapsPermissiveObject() {
    // not: {} parses the inner empty schema as the permissive ObjectSchema.
    Schema s = SchemaParser.parse(Map.of("not", Map.of()));
    assertThat(s).isInstanceOf(NotSchema.class);
    assertThat(((NotSchema) s).schema()).isInstanceOf(ObjectSchema.class);
  }

  @Test
  void parsesImplicitObjectFromShapeKeywords() {
    // Schema with object-shape keywords but no explicit type still produces
    // an ObjectSchema (parseBaseIfPresent's implicit-object branch).
    Schema s =
        SchemaParser.parse(
            Map.of("required", List.of("x"), "properties", Map.of("x", Map.of("type", "string"))));
    assertThat(s).isInstanceOf(ObjectSchema.class);
    ObjectSchema obj = (ObjectSchema) s;
    assertThat(obj.types()).isEmpty();
    assertThat(obj.required()).containsExactly("x");
    assertThat(obj.properties().get("x")).isInstanceOf(StringSchema.class);
  }

  @Test
  void parsesImplicitArrayFromShapeKeywords() {
    // Schema with array-shape keywords but no explicit type still produces
    // an ArraySchema (parseBaseIfPresent's implicit-array branch).
    Schema s = SchemaParser.parse(Map.of("items", Map.of("type", "integer"), "minItems", 1));
    assertThat(s).isInstanceOf(ArraySchema.class);
    ArraySchema arr = (ArraySchema) s;
    assertThat(arr.types()).isEmpty();
    assertThat(arr.items()).isInstanceOf(IntegerSchema.class);
    assertThat(arr.minItems()).isEqualTo(1);
  }

  @Test
  void oneOfContainingNestedAnyOfRecurses() {
    // Pins that combinator branches are themselves passed through parse(),
    // so nested combinators survive intact.
    Schema s =
        SchemaParser.parse(
            Map.of(
                "oneOf",
                List.of(
                    Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))),
                    Map.of("type", "boolean"))));
    assertThat(s).isInstanceOf(OneOfSchema.class);
    OneOfSchema one = (OneOfSchema) s;
    assertThat(one.options()).hasSize(2);
    assertThat(one.options().get(0)).isInstanceOf(AnyOfSchema.class);
    assertThat(((AnyOfSchema) one.options().get(0)).options()).hasSize(2);
    assertThat(one.options().get(1)).isInstanceOf(BooleanSchema.class);
  }
}
