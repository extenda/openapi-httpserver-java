package com.retailsvc.http.internal;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.validate.ValidationError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Carrier for an RFC 9457 problem+json document. Serialized by {@link ProblemDetailRenderer}; the
 * wire shape is the RFC core members (type, title, status, detail) plus an {@code errors} extension
 * array. Each {@link Entry} locates one validation failure with a JSON-Pointer-in-fragment {@code
 * pointer} and the failed {@code keyword}. {@code type} is always {@code about:blank}, so {@code
 * title} is advisory per the RFC.
 */
public record ProblemDetail(
    String type, String title, int status, String detail, List<Entry> errors) {

  public ProblemDetail {
    if (errors == null) {
      errors = List.of();
    }
  }

  /** One validation failure: its body location, the failed keyword, and a human-readable detail. */
  public record Entry(String pointer, String keyword, String detail) {}

  private static final String DEFAULT_TYPE = "about:blank";

  public static ProblemDetail forValidation(ValidationError e) {
    return new ProblemDetail(DEFAULT_TYPE, "Bad Request", 400, e.message(), entriesOf(e));
  }

  public static ProblemDetail forBadRequest(BadRequestException e) {
    List<Entry> errors =
        e.pointer()
            .map(p -> List.of(new Entry(fragment(p), e.keyword().orElse(null), e.getMessage())))
            .orElseGet(List::of);
    return new ProblemDetail(
        DEFAULT_TYPE, titleFor(e.status()), e.status(), e.getMessage(), errors);
  }

  /**
   * Flattens a validation error into ordered {@code errors} entries: the failed branches of a
   * combinator (one each), or the single leaf otherwise. Multiple sources are sorted deepest
   * pointer first (most-likely-intended branch) and de-duplicated on exact equality.
   */
  private static List<Entry> entriesOf(ValidationError e) {
    List<ValidationError> sources = e.branches().isEmpty() ? List.of(e) : e.branches();
    if (sources.size() > 1) {
      sources = new ArrayList<>(sources);
      sources.sort(Comparator.comparingInt((ValidationError s) -> depth(s.pointer())).reversed());
    }
    LinkedHashSet<Entry> entries = new LinkedHashSet<>();
    for (ValidationError s : sources) {
      entries.add(new Entry(fragment(s.pointer()), s.keyword(), s.message()));
    }
    return new ArrayList<>(entries);
  }

  private static String fragment(String pointer) {
    return "#" + pointer;
  }

  private static int depth(String pointer) {
    int n = 0;
    for (int i = 0; i < pointer.length(); i++) {
      if (pointer.charAt(i) == '/') {
        n++;
      }
    }
    return n;
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
