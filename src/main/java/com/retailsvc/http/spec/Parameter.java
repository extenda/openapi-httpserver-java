package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;

public record Parameter(String name, Location in, boolean required, Schema schema) {
  public enum Location {
    PATH,
    QUERY,
    HEADER,
    COOKIE
  }
}
