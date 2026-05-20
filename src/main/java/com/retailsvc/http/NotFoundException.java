package com.retailsvc.http;

/** Thrown when a request path does not match any route declared in the OpenAPI spec. */
public final class NotFoundException extends RuntimeException {
  /**
   * Creates a new exception with the given detail message.
   *
   * @param message human-readable explanation of the missing route
   */
  public NotFoundException(String message) {
    super(message);
  }
}
