package com.retailsvc.http.validate;

import java.util.List;
import java.util.Objects;

public record ValidationError(
    String pointer,
    String keyword,
    String message,
    Object rejectedValue,
    List<ValidationError> branches) {

  public ValidationError {
    Objects.requireNonNull(
        branches, "branches must not be null; use the 4-arg constructor for a leaf error");
    branches = List.copyOf(branches);
  }

  /** Leaf error with no branch sub-errors. */
  public ValidationError(String pointer, String keyword, String message, Object rejectedValue) {
    this(pointer, keyword, message, rejectedValue, List.of());
  }
}
