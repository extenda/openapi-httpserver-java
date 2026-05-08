package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;
import java.util.Locale;

public record Parameter(String name, Location in, boolean required, Schema schema, String pointer) {

  public Parameter(String name, Location in, boolean required, Schema schema) {
    this(name, in, required, schema, "/" + in.name().toLowerCase(Locale.ROOT) + "/" + name);
  }

  public enum Location {
    PATH,
    QUERY,
    HEADER,
    COOKIE
  }
}
