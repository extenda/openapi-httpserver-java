package com.retailsvc.http.openapi.exceptions;

public class LoadSpecificationException extends RuntimeException {

  public LoadSpecificationException(String message) {
    super(message);
  }

  public LoadSpecificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
