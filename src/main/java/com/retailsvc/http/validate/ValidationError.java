package com.retailsvc.http.validate;

import java.util.List;

public record ValidationError(
    String pointer,
    String keyword,
    String message,
    Object rejectedValue,
    List<ValidationError> branches) {

  public ValidationError {
    branches = List.copyOf(branches);
  }

  /** Leaf error with no branch sub-errors. */
  public ValidationError(String pointer, String keyword, String message, Object rejectedValue) {
    this(pointer, keyword, message, rejectedValue, List.of());
  }
}
