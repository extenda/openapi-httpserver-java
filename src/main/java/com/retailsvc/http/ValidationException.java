package com.retailsvc.http;

import com.retailsvc.http.validate.ValidationError;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime exception raised when a request fails OpenAPI schema validation. */
public final class ValidationException extends RuntimeException {
  /**
   * Counts every {@code ValidationException} ever constructed. Used to assert that the validator
   * does not use exceptions as control flow on the happy path: a successful validation of a body
   * containing {@code oneOf}/{@code anyOf} branches should leave this counter unchanged.
   */
  public static final AtomicLong CONSTRUCTIONS = new AtomicLong();

  private final transient ValidationError error;

  /**
   * Creates a new validation exception wrapping the given error.
   *
   * @param error the validation error that triggered this exception
   */
  public ValidationException(ValidationError error) {
    super(error.pointer() + " [" + error.keyword() + "] " + error.message());
    this.error = error;
    CONSTRUCTIONS.incrementAndGet();
  }

  /**
   * Returns the underlying validation error.
   *
   * @return the validation error wrapped by this exception
   */
  public ValidationError error() {
    return error;
  }
}
