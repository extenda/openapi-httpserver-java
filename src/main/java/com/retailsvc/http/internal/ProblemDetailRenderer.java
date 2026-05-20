package com.retailsvc.http.internal;

import com.retailsvc.http.validate.ValidationError;

/**
 * Renders a {@link ValidationError} as an RFC 7807 {@code application/problem+json} document.
 *
 * <p>Hand-rolled to avoid pulling in a JSON library; only six fixed fields are emitted, all with
 * known shapes, so a writer-from-scratch is safer than a generic encoder.
 */
public final class ProblemDetailRenderer {

  private static final String PROBLEM_TYPE = "about:blank";
  private static final String PROBLEM_TITLE = "Bad Request";
  private static final int PROBLEM_STATUS = 400;

  /** Initial capacity of the JSON buffer; sized for a typical problem-detail document. */
  private static final int INITIAL_BUFFER_CAPACITY = 128;

  private ProblemDetailRenderer() {}

  public static String render(int status, String title, String detail) {
    StringBuilder out = new StringBuilder(INITIAL_BUFFER_CAPACITY);
    out.append('{');
    JsonStrings.appendStringField(out, "type", PROBLEM_TYPE);
    out.append(',');
    JsonStrings.appendStringField(out, "title", title);
    out.append(',');
    appendIntField(out, "status", status);
    out.append(',');
    JsonStrings.appendStringField(out, "detail", detail);
    out.append('}');
    return out.toString();
  }

  public static String render(ValidationError error) {
    StringBuilder out = new StringBuilder(INITIAL_BUFFER_CAPACITY);
    out.append('{');
    JsonStrings.appendStringField(out, "type", PROBLEM_TYPE);
    out.append(',');
    JsonStrings.appendStringField(out, "title", PROBLEM_TITLE);
    out.append(',');
    appendIntField(out, "status", PROBLEM_STATUS);
    out.append(',');
    JsonStrings.appendStringField(out, "detail", error.message());
    out.append(',');
    JsonStrings.appendStringField(out, "pointer", error.pointer());
    out.append(',');
    JsonStrings.appendStringField(out, "keyword", error.keyword());
    out.append('}');
    return out.toString();
  }

  private static void appendIntField(StringBuilder out, String name, int value) {
    out.append('"').append(name).append("\":").append(value);
  }
}
