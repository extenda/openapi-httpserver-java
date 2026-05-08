package com.retailsvc.http;

public final class MissingOperationHandlerException extends RuntimeException {
  public MissingOperationHandlerException(String operationId) {
    super("no handler registered for operationId=" + operationId);
  }
}
