package com.retailsvc.http;

import com.retailsvc.http.validate.ValidationError;

public final class ValidationException extends RuntimeException {
  private final ValidationError error;

  public ValidationException(ValidationError error) {
    super(error.pointer() + " [" + error.keyword() + "] " + error.message());
    this.error = error;
  }

  public ValidationError error() {
    return error;
  }
}
