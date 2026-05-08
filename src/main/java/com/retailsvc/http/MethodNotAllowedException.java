package com.retailsvc.http;

import com.retailsvc.http.spec.HttpMethod;
import java.util.Set;

public final class MethodNotAllowedException extends RuntimeException {
  private final Set<HttpMethod> allowed;

  public MethodNotAllowedException(Set<HttpMethod> allowed) {
    super("method not allowed; allowed=" + allowed);
    this.allowed = Set.copyOf(allowed);
  }

  public Set<HttpMethod> allowed() {
    return allowed;
  }
}
