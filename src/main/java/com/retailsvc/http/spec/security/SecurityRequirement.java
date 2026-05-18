package com.retailsvc.http.spec.security;

import java.util.List;
import java.util.Map;

/**
 * One OR-branch in a {@code security} list. Each entry in {@link #schemes} is AND-ed: every scheme
 * name must be satisfied for the requirement to hold. Scopes are preserved but unused in v1.
 */
public record SecurityRequirement(Map<String, List<String>> schemes) {
  public SecurityRequirement {
    schemes = Map.copyOf(schemes);
  }
}
