package com.retailsvc.http.openapi.model;

/**
 * The 'parameter' describes a single operation parameter.
 *
 * @see <a href="https://swagger.io/specification/#parameter-object">Parameter Object</a>
 */
public record Parameter(String $ref, String in, String name, boolean required, Schema schema) {

  private static final String HEADER = "header";
  private static final String QUERY = "query";
  private static final String PATH = "path";

  public boolean isHeader() {
    return in != null && in.equalsIgnoreCase(HEADER);
  }

  public boolean isPath() {
    return in != null && in.equalsIgnoreCase(PATH);
  }

  public boolean isQuery() {
    return in != null && in.equalsIgnoreCase(QUERY);
  }
}
