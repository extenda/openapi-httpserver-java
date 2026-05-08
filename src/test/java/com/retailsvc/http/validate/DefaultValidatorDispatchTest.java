package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.AllOfSchema;
import com.retailsvc.http.spec.schema.AnyOfSchema;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.NotSchema;
import com.retailsvc.http.spec.schema.NullSchema;
import com.retailsvc.http.spec.schema.OneOfSchema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultValidatorDispatchTest {
  private final Validator v =
      new DefaultValidator(
          name -> {
            throw new AssertionError("no refs");
          });

  @Test
  void nullSchemaAcceptsNull() {
    v.validate(null, new NullSchema(), "");
  }

  @Test
  void nullSchemaRejectsNonNull() {
    var schema = new NullSchema();
    assertThatThrownBy(() -> v.validate("x", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void booleanSchemaAcceptsBoolean() {
    v.validate(true, new BooleanSchema(Set.of(TypeName.BOOLEAN)), "/v");
  }

  @Test
  void booleanSchemaRejectsString() {
    var schema = new BooleanSchema(Set.of(TypeName.BOOLEAN));
    assertThatThrownBy(() -> v.validate("x", schema, "/v")).isInstanceOf(ValidationException.class);
  }

  private StringSchema stringSchema(Integer min, Integer max) {
    return new StringSchema(Set.of(TypeName.STRING), null, min, max, null, null);
  }

  @Test
  void allOfPassesWhenAllBranchesPass() {
    var schema = new AllOfSchema(List.of(stringSchema(1, null), stringSchema(null, 10)));
    v.validate("hello", schema, "/v");
  }

  @Test
  void allOfPropagatesFirstFailingBranch() {
    var schema = new AllOfSchema(List.of(stringSchema(1, null), stringSchema(null, 3)));
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("maxLength");
  }

  @Test
  void anyOfPassesWhenOneBranchPasses() {
    var schema = new AnyOfSchema(List.of(stringSchema(100, null), stringSchema(null, 10)));
    v.validate("hello", schema, "/v");
  }

  @Test
  void anyOfFailsWhenNoBranchMatches() {
    var schema = new AnyOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)));
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("anyOf");
  }

  @Test
  void oneOfPassesWhenExactlyOneBranchMatches() {
    // value "hello" — len 5. branch[0] requires min 100 (fails), branch[1] max 10 (passes).
    var schema = new OneOfSchema(List.of(stringSchema(100, null), stringSchema(null, 10)));
    v.validate("hello", schema, "/v");
  }

  @Test
  void oneOfFailsWhenZeroBranchesMatch() {
    var schema = new OneOfSchema(List.of(stringSchema(100, null), stringSchema(null, 2)));
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            t -> {
              var err = ((ValidationException) t).error();
              assertThat(err.keyword()).isEqualTo("oneOf");
              assertThat(err.message()).contains("matched 0 of 2");
            });
  }

  @Test
  void oneOfFailsWhenTwoBranchesMatch() {
    // value "hello" — both branches accept.
    var schema = new OneOfSchema(List.of(stringSchema(null, 10), stringSchema(1, null)));
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            t -> {
              var err = ((ValidationException) t).error();
              assertThat(err.keyword()).isEqualTo("oneOf");
              assertThat(err.message()).contains("matched 2 of 2");
            });
  }

  @Test
  void notPassesWhenInnerFails() {
    var schema = new NotSchema(stringSchema(100, null));
    v.validate("hello", schema, "/v");
  }

  @Test
  void notFailsWhenInnerPasses() {
    var schema = new NotSchema(stringSchema(null, 10));
    assertThatThrownBy(() -> v.validate("hello", schema, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("not");
  }

  @Test
  void allOfWithEmptyPartsAlwaysPasses() {
    // Empty allOf is vacuously true per JSON Schema 2020-12.
    v.validate("anything", new AllOfSchema(List.of()), "/v");
  }

  @Test
  void anyOfWithEmptyOptionsAlwaysFails() {
    assertThatThrownBy(() -> v.validate("anything", new AnyOfSchema(List.of()), "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("anyOf");
  }

  @Test
  void oneOfWithEmptyOptionsAlwaysFails() {
    assertThatThrownBy(() -> v.validate("anything", new OneOfSchema(List.of()), "/v"))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            t -> {
              var err = ((ValidationException) t).error();
              assertThat(err.keyword()).isEqualTo("oneOf");
              assertThat(err.message()).contains("matched 0 of 0");
            });
  }

  @Test
  void notWithNullSchemaRejectsNull() {
    // not(NullSchema) — inner accepts null, outer must reject.
    assertThatThrownBy(() -> v.validate(null, new NotSchema(new NullSchema()), "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("not");
  }

  @Test
  void anyOfMatchesNullViaNullSchema() {
    // anyOf containing a NullSchema branch must pass for a null value.
    var schema = new AnyOfSchema(List.of(stringSchema(1, null), new NullSchema()));
    v.validate(null, schema, "/v");
  }
}
