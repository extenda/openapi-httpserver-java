package com.retailsvc.http.openapi.exceptions;

public class OperationIdNotFoundException extends RuntimeException
    implements NotFoundTypeException {

  public OperationIdNotFoundException(String method, String path) {
    super("No operationId found for %s: %s".formatted(method.toUpperCase(), path));
  }
}
