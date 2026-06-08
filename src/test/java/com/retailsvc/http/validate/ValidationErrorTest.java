package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationErrorTest {

  @Test
  void convenienceConstructorDefaultsToNoBranches() {
    var e = new ValidationError("/x", "type", "expected string", null);
    assertThat(e.branches()).isEmpty();
  }

  @Test
  void canonicalConstructorCopiesBranchesDefensively() {
    var branch = new ValidationError("/x/y", "type", "expected number", "s");
    var mutable = new ArrayList<ValidationError>(List.of(branch));

    var e = new ValidationError("/x", "oneOf", "matched 0 of 1 oneOf branches", "s", mutable);
    mutable.clear();

    assertThat(e.branches()).containsExactly(branch);
  }

  @Test
  void nullBranchesArgumentThrows() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ValidationError("/x", "oneOf", "summary", "s", null))
        .withMessageContaining("branches");
  }
}
