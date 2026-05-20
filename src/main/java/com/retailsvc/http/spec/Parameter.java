package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;
import java.util.Locale;

/**
 * OpenAPI {@code parameter} object describing a single path, query, header or cookie parameter.
 *
 * @param name parameter name as it appears on the wire
 * @param in where the parameter is carried in the request
 * @param required whether the parameter must be present
 * @param schema schema used to validate the parameter value
 * @param pointer JSON Pointer-style location used in validation error messages
 */
public record Parameter(String name, Location in, boolean required, Schema schema, String pointer) {

  /**
   * Convenience constructor that derives {@code pointer} from {@code in} and {@code name}.
   *
   * @param name parameter name as it appears on the wire
   * @param in where the parameter is carried in the request
   * @param required whether the parameter must be present
   * @param schema schema used to validate the parameter value
   */
  public Parameter(String name, Location in, boolean required, Schema schema) {
    this(name, in, required, schema, "/" + in.name().toLowerCase(Locale.ROOT) + "/" + name);
  }

  /** Where in an HTTP request a {@link Parameter} is carried. */
  public enum Location {
    /** Path parameter, substituted into a templated URL segment. */
    PATH,
    /** Query string parameter. */
    QUERY,
    /** Request header. */
    HEADER,
    /** Cookie value. */
    COOKIE
  }
}
