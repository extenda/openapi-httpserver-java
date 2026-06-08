package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.internal.ProblemDetail.Entry;
import com.retailsvc.http.validate.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProblemDetailTest {

  @Test
  void leafErrorBecomesSingleFragmentEntry() {
    var pd =
        ProblemDetail.forValidation(new ValidationError("/age", "type", "expected integer", "x"));
    assertThat(pd.errors()).containsExactly(new Entry("#/age", "type", "expected integer"));
  }

  @Test
  void rootPointerFragmentsToHash() {
    var pd = ProblemDetail.forValidation(new ValidationError("", "type", "expected object", 1));
    assertThat(pd.errors()).containsExactly(new Entry("#", "type", "expected object"));
  }

  @Test
  void branchesSortedDeepestPointerFirst() {
    var deep = new ValidationError("/pet/collar/size", "type", "expected integer", "big");
    var shallow = new ValidationError("/pet/bark", "type", "expected boolean", 7);
    var combinator =
        new ValidationError(
            "/pet", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(shallow, deep));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/pet/collar/size", "type", "expected integer"),
            new Entry("#/pet/bark", "type", "expected boolean"));
  }

  @Test
  void identicalBranchErrorsAreDeduplicated() {
    var a = new ValidationError("/kind", "required", "required property missing", null);
    var b = new ValidationError("/kind", "required", "required property missing", null);
    var combinator =
        new ValidationError("", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(a, b));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(new Entry("#/kind", "required", "required property missing"));
  }

  @Test
  void equalDepthBranchesKeepSchemaOrder() {
    var first = new ValidationError("/radius", "required", "required property missing", null);
    var second = new ValidationError("/kind", "enum", "value not in enum", "triangle");
    var combinator =
        new ValidationError(
            "", "oneOf", "matched 0 of 2 oneOf branches", null, List.of(first, second));

    var pd = ProblemDetail.forValidation(combinator);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/radius", "required", "required property missing"),
            new Entry("#/kind", "enum", "value not in enum"));
  }

  @Test
  void nestedCombinatorBranchSurfacesAsSingleSummaryEntry() {
    // A branch that is itself a failed combinator contributes ONE entry carrying its own
    // summary; its sub-branches are not recursively expanded. The hidden sub-leaf is deep
    // (/pet/reward/x/y/z) but does not surface, so the nested entry sorts by its own shallow
    // pointer (/pet/reward, depth 2), behind the genuinely deeper sibling leaf (depth 3).
    var hiddenSubLeaf = new ValidationError("/pet/reward/x/y/z", "type", "expected number", "s");
    var nestedCombinator =
        new ValidationError(
            "/pet/reward", "oneOf", "matched 0 of 3 oneOf branches", null, List.of(hiddenSubLeaf));
    var deeperLeaf = new ValidationError("/pet/collar/size", "type", "expected integer", "big");
    var top =
        new ValidationError(
            "/pet",
            "oneOf",
            "matched 0 of 2 oneOf branches",
            null,
            List.of(nestedCombinator, deeperLeaf));

    var pd = ProblemDetail.forValidation(top);

    assertThat(pd.errors())
        .containsExactly(
            new Entry("#/pet/collar/size", "type", "expected integer"),
            new Entry("#/pet/reward", "oneOf", "matched 0 of 3 oneOf branches"));
  }

  @Test
  void badRequestWithoutPointerHasEmptyErrors() {
    var pd = ProblemDetail.forBadRequest(new BadRequestException("nope"));
    assertThat(pd.errors()).isEmpty();
  }

  @Test
  void badRequestWithPointerBecomesSingleEntry() {
    var pd = ProblemDetail.forBadRequest(new BadRequestException(409, "taken", "/email", "unique"));
    assertThat(pd.errors()).containsExactly(new Entry("#/email", "unique", "taken"));
  }
}
