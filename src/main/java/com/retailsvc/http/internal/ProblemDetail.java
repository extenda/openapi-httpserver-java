package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.validate.ValidationError;
import java.util.Map;

/**
 * Carrier for an RFC 7807 problem+json document. Serialized by the registered JSON {@code
 * TypeMapper}; the wire shape and field-order are whatever the configured mapper produces — title
 * is advisory per RFC 7807 since {@code type} is always {@code about:blank}.
 */
public record ProblemDetail(
    String type, String title, int status, String detail, String pointer, String keyword) {

  private static final String DEFAULT_TYPE = "about:blank";

  public static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(
        DEFAULT_TYPE, "Bad Request", 400, e.message(), e.pointer(), e.keyword());
  }

  public static ProblemDetail forBadRequest(BadRequestException e) {
    return new ProblemDetail(
        DEFAULT_TYPE,
        titleFor(e.status()),
        e.status(),
        e.getMessage(),
        e.pointer().orElse(null),
        e.keyword().orElse(null));
  }

  private static final Map<Integer, String> TITLES =
      Map.of(
          400, "Bad Request",
          401, "Unauthorized",
          403, "Forbidden",
          404, "Not Found",
          405, "Method Not Allowed",
          409, "Conflict",
          410, "Gone",
          412, "Precondition Failed",
          415, "Unsupported Media Type",
          422, "Unprocessable Content");

  private static String titleFor(int status) {
    return TITLES.getOrDefault(status, "Bad Request");
  }
}
