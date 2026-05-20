package com.retailsvc.http;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Set;

/** Thrown when a request targets a known path with an HTTP method that is not declared for it. */
public final class MethodNotAllowedException extends RuntimeException {
  /** The set of HTTP methods that the matched path accepts. */
  private final Set<HttpMethod> allowed;

  /**
   * Creates a new exception carrying the methods the path actually accepts.
   *
   * @param allowed methods declared for the matched path
   */
  public MethodNotAllowedException(Set<HttpMethod> allowed) {
    super("method not allowed; allowed=" + allowed);
    this.allowed = Set.copyOf(allowed);
  }

  /**
   * Returns the HTTP methods allowed for the matched path.
   *
   * @return immutable set of allowed methods
   */
  public Set<HttpMethod> allowed() {
    return allowed;
  }
}
