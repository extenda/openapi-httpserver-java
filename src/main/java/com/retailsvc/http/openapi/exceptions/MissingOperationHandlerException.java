package com.retailsvc.http.openapi.exceptions;

public class MissingOperationHandlerException extends RuntimeException {
  public MissingOperationHandlerException(String operationId) {
    super("No handler found for operation %s".formatted(operationId));
  }
}
