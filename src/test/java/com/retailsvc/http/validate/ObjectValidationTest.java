package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.AdditionalProperties;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObjectValidationTest {
  private final Validator v =
      new DefaultValidator(
          name -> {
            throw new AssertionError();
          });

  private ObjectSchema obj(
      Map<String, Schema> props, List<String> required, AdditionalProperties ap) {
    return new ObjectSchema(Set.of(TypeName.OBJECT), props, required, ap, null, null);
  }

  @Test
  void requiredFieldMissing() {
    var s =
        obj(
            Map.of("name", new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null)),
            List.of("name"),
            new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate(Map.of(), s, ""))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("required");
  }

  @Test
  void propertyValidatedAtPointer() {
    var s =
        obj(
            Map.of("name", new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null)),
            List.of(),
            new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate(Map.of("name", "ab"), s, ""))
        .extracting(t -> ((ValidationException) t).error().pointer())
        .isEqualTo("/name");
  }

  @Test
  void additionalPropertiesAllowedByDefault() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Allowed());
    assertThatCode(() -> v.validate(Map.of("extra", "x"), s, "")).doesNotThrowAnyException();
  }

  @Test
  void additionalPropertiesForbidden() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Forbidden());
    assertThatThrownBy(() -> v.validate(Map.of("extra", "x"), s, ""))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("additionalProperties");
  }

  @Test
  void rejectsNonObject() {
    var s = obj(Map.of(), List.of(), new AdditionalProperties.Allowed());
    assertThatThrownBy(() -> v.validate("nope", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }
}
