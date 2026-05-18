package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.AdditionalProperties;
import com.retailsvc.http.spec.schema.AnyOfSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.OneOfSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Performance contract: branch-selecting schemas ({@code oneOf} / {@code anyOf}) must select
 * matching branches by inspecting a return value, not by catching {@link ValidationException}.
 * Constructing a {@link Throwable} on every non-matching branch was a measurable hot-path cost (see
 * the issue that prompted this refactor). These tests fail if anyone reintroduces exception-driven
 * control flow.
 */
class ValidatorNoThrowOnHappyPathTest {

  private final Validator validator =
      new DefaultValidator(
          name -> {
            throw new AssertionError("no refs");
          });

  private static Schema stringOrNumber() {
    return new OneOfSchema(
        List.of(
            new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null, Map.of()),
            new NumberSchema(
                Set.of(TypeName.NUMBER), null, null, null, null, null, null, Map.of())),
        Map.of());
  }

  private static Schema attributesMap() {
    return new ObjectSchema(
        Set.of(TypeName.OBJECT),
        Map.of(),
        List.of(),
        new AdditionalProperties.SchemaConstraint(stringOrNumber()),
        null,
        null,
        Map.of());
  }

  @Test
  void successfulOneOfDoesNotConstructValidationException() {
    long before = ValidationException.CONSTRUCTIONS.get();

    validator.validate("hello", stringOrNumber(), "/v");
    validator.validate(42, stringOrNumber(), "/v");
    validator.validate(Map.of("a", "x", "b", 7, "c", "1234567890"), attributesMap(), "/v");

    assertThat(ValidationException.CONSTRUCTIONS.get() - before)
        .as("no ValidationException should be constructed on a valid oneOf body")
        .isZero();
  }

  @Test
  void successfulAnyOfDoesNotConstructValidationException() {
    Schema anyOfStringOrNumber =
        new AnyOfSchema(
            List.of(
                new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null, Map.of()),
                new NumberSchema(
                    Set.of(TypeName.NUMBER), null, null, null, null, null, null, Map.of())),
            Map.of());

    long before = ValidationException.CONSTRUCTIONS.get();

    validator.validate("hello", anyOfStringOrNumber, "/v");
    validator.validate(42, anyOfStringOrNumber, "/v");

    assertThat(ValidationException.CONSTRUCTIONS.get() - before)
        .as("no ValidationException should be constructed on a valid anyOf body")
        .isZero();
  }

  @Test
  void failingOneOfConstructsExactlyOneValidationException() {
    long before = ValidationException.CONSTRUCTIONS.get();
    var schema = stringOrNumber();

    assertThatThrownBy(() -> validator.validate(true, schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("oneOf");

    assertThat(ValidationException.CONSTRUCTIONS.get() - before)
        .as("exactly one ValidationException — only at the boundary, not per branch")
        .isOne();
  }
}
