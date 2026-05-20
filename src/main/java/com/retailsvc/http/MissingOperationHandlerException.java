package com.retailsvc.http;

/** Thrown when a request resolves to an OpenAPI operationId that has no registered handler. */
public final class MissingOperationHandlerException extends RuntimeException {
  /**
   * Creates a new exception identifying the unregistered operation.
   *
   * @param operationId the OpenAPI operationId with no handler bound
   */
  public MissingOperationHandlerException(String operationId) {
    super("no handler registered for operationId=" + operationId);
  }
}
