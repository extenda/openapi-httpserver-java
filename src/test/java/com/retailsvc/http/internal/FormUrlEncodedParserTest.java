package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormUrlEncodedParserTest {

  private final FormUrlEncodedParser parser = new FormUrlEncodedParser();

  @Test
  void emptyBodyReturnsEmptyMap() {
    assertThat(parser.parse(new byte[0], null)).isEmpty();
  }

  @Test
  void singleField() {
    assertThat(parser.parse("a=1".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "1"));
  }

  @Test
  void multipleFields() {
    Map<String, Object> result = parser.parse("a=1&b=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
  }

  @Test
  void repeatedKeyBecomesList() {
    Map<String, Object> result = parser.parse("a=1&a=2".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("a", List.of("1", "2")));
  }

  @Test
  void threeRepeatedValues() {
    Map<String, Object> result = parser.parse("x=1&x=2&x=3".getBytes(StandardCharsets.UTF_8), null);
    assertThat(result).containsExactly(Map.entry("x", List.of("1", "2", "3")));
  }

  @Test
  void emptyValue() {
    assertThat(parser.parse("a=".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void keyWithoutEquals() {
    assertThat(parser.parse("a".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", ""));
  }

  @Test
  void percentDecodesKeyAndValue() {
    assertThat(parser.parse("a%20b=c%26d".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a b", "c&d"));
  }

  @Test
  void plusIsSpace() {
    assertThat(parser.parse("a=b+c".getBytes(StandardCharsets.UTF_8), null))
        .containsExactly(Map.entry("a", "b c"));
  }

  @Test
  void charsetFromHeader() {
    byte[] iso = "x=räka".getBytes(StandardCharsets.ISO_8859_1);
    assertThat(parser.parse(iso, "application/x-www-form-urlencoded; charset=iso-8859-1"))
        .containsExactly(Map.entry("x", "räka"));
  }

  @Test
  void coercesIntegerProperty() {
    IntegerSchema intSchema = anIntegerSchema();
    ObjectSchema bodySchema = anObjectSchema(Map.of("age", intSchema));

    Map<String, Object> out =
        FormBodyCoercion.coerce(
            parser.parse("age=30".getBytes(StandardCharsets.UTF_8), null), bodySchema);

    assertThat(out).containsExactly(Map.entry("age", 30L));
  }

  @Test
  void coercesArrayOfIntegersProperty() {
    IntegerSchema intItems = anIntegerSchema();
    ArraySchema arrSchema = anArraySchemaOf(intItems);
    ObjectSchema bodySchema = anObjectSchema(Map.of("ids", arrSchema));

    Map<String, Object> out =
        FormBodyCoercion.coerce(
            parser.parse("ids=1&ids=2".getBytes(StandardCharsets.UTF_8), null), bodySchema);

    assertThat(out).containsExactly(Map.entry("ids", List.of(1L, 2L)));
  }

  @Test
  void coercionFailureThrowsValidationExceptionAtPropertyPointer() {
    IntegerSchema intSchema = anIntegerSchema();
    ObjectSchema bodySchema = anObjectSchema(Map.of("age", intSchema));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                FormBodyCoercion.coerce(
                    parser.parse("age=abc".getBytes(StandardCharsets.UTF_8), null), bodySchema))
        .isInstanceOf(com.retailsvc.http.ValidationException.class)
        .extracting("error.pointer", "error.keyword")
        .containsExactly("/age", "type");
  }

  @Test
  void unknownPropertyPassesThroughUnchanged() {
    ObjectSchema bodySchema = anObjectSchema(Map.of());

    Map<String, Object> out =
        FormBodyCoercion.coerce(
            parser.parse("anything=v".getBytes(StandardCharsets.UTF_8), null), bodySchema);

    assertThat(out).containsExactly(Map.entry("anything", "v"));
  }

  @Test
  void nonObjectSchemaReturnsRawMap() {
    StringSchema strSchema = aStringSchema();

    Map<String, Object> out =
        FormBodyCoercion.coerce(
            parser.parse("a=1".getBytes(StandardCharsets.UTF_8), null), strSchema);

    assertThat(out).containsExactly(Map.entry("a", "1"));
  }

  private static IntegerSchema anIntegerSchema() {
    return new IntegerSchema(
        Set.of(TypeName.INTEGER), null, null, null, null, null, null, Map.of());
  }

  private static StringSchema aStringSchema() {
    return new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null, Map.of());
  }

  private static ArraySchema anArraySchemaOf(Schema items) {
    return new ArraySchema(Set.of(TypeName.ARRAY), items, null, null, false, Map.of());
  }

  private static ObjectSchema anObjectSchema(Map<String, Schema> properties) {
    return new ObjectSchema(
        Set.of(TypeName.OBJECT), properties, List.of(), null, null, null, Map.of());
  }
}
