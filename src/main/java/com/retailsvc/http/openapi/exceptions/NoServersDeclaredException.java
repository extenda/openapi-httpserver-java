package com.retailsvc.http.openapi.exceptions;

public class NoServersDeclaredException extends RuntimeException implements NotFoundTypeException {

  public NoServersDeclaredException() {
    super("No server urls found");
  }
}
