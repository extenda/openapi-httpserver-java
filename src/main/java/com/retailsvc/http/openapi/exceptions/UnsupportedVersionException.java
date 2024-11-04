package com.retailsvc.http.openapi.exceptions;

public class UnsupportedVersionException extends RuntimeException {

  public UnsupportedVersionException(String version) {
    super("Version %s is not supported.".formatted(version));
  }
}
