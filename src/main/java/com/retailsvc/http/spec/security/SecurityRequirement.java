package com.retailsvc.http.spec.security;

import java.util.List;
import java.util.Map;

/**
 * One OR-branch in a {@code security} list. Each entry in {@link #schemes} is AND-ed: every scheme
 * name must be satisfied for the requirement to hold. Scopes are preserved but unused in v1.
 *
 * @param schemes map from security-scheme name (as declared in {@code components.securitySchemes})
 *     to the list of OAuth2 / OpenID Connect scopes required for that scheme. An empty list means
 *     "any scope" / "no scopes required" (also used for non-OAuth schemes such as API key or HTTP
 *     auth where scopes do not apply).
 */
public record SecurityRequirement(Map<String, List<String>> schemes) {
  /** Canonical constructor that defensively copies {@code schemes} into an unmodifiable map. */
  public SecurityRequirement {
    schemes = Map.copyOf(schemes);
  }
}
