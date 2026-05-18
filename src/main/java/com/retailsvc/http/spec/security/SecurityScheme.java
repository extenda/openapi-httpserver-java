package com.retailsvc.http.spec.security;

import java.util.Optional;

public sealed interface SecurityScheme
    permits SecurityScheme.ApiKey,
        SecurityScheme.HttpBearer,
        SecurityScheme.HttpBasic,
        SecurityScheme.Unsupported {

  record ApiKey(String name, Location location) implements SecurityScheme {
    public enum Location {
      HEADER,
      QUERY,
      COOKIE
    }
  }

  record HttpBearer(Optional<String> bearerFormat) implements SecurityScheme {}

  record HttpBasic() implements SecurityScheme {}

  /** Parsed but unsupported in v1 (oauth2, openIdConnect, mutualTLS). */
  record Unsupported(String type) implements SecurityScheme {}
}
