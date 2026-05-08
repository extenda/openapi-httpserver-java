package com.retailsvc.http.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-request data populated by {@link RequestPreparationFilter} and read by handlers via
 * {@link com.retailsvc.http.Request}. Bound to a {@link ScopedValue} for the duration of a single
 * request — never written to the {@code HttpExchange}'s context-shared attribute map.
 *
 * <p>{@code equals}, {@code hashCode}, and {@code toString} are overridden because the record
 * carries a {@code byte[]} component: the auto-generated implementations use reference equality on
 * arrays, which would treat structurally-equal contexts as different.
 */
public record RequestContext(
    byte[] body, Object parsedBody, String operationId, Map<String, String> pathParameters) {

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RequestContext other)) {
      return false;
    }
    return Arrays.equals(body, other.body)
        && Objects.equals(parsedBody, other.parsedBody)
        && Objects.equals(operationId, other.operationId)
        && Objects.equals(pathParameters, other.pathParameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(body), parsedBody, operationId, pathParameters);
  }

  @Override
  public String toString() {
    return "RequestContext[body=byte["
        + (body == null ? 0 : body.length)
        + "], parsedBody="
        + parsedBody
        + ", operationId="
        + operationId
        + ", pathParameters="
        + pathParameters
        + "]";
  }
}
