package com.retailsvc.http.openapi.exceptions;

public class BadRequestException extends RuntimeException implements BadRequestTypeException {

  public BadRequestException() {
    super();
  }

  public BadRequestException(String message) {
    super(message);
  }
}
