package com.retailsvc.http.spec.security;

import java.util.Optional;

/**
 * Models an OpenAPI 3.1 {@code securitySchemes} entry.
 *
 * <p>This sealed interface enumerates the security scheme variants the library understands natively
 * ({@link ApiKey}, {@link HttpBearer}, {@link HttpBasic}) and provides an {@link Unsupported}
 * fallback so that specs declaring scheme types not yet implemented in v1 (such as {@code oauth2},
 * {@code openIdConnect} or {@code mutualTLS}) can still be parsed without failing the whole spec
 * load.
 *
 * <p>Each permitted variant is a {@code record}, enabling exhaustive pattern matching at the call
 * site.
 */
public sealed interface SecurityScheme
    permits SecurityScheme.ApiKey,
        SecurityScheme.HttpBearer,
        SecurityScheme.HttpBasic,
        SecurityScheme.Unsupported {

  /**
   * OpenAPI {@code type: apiKey} security scheme.
   *
   * <p>Represents an API key transported either as a header, query parameter or cookie. The {@code
   * name} corresponds to the OpenAPI {@code name} property and identifies the header / query /
   * cookie key whose value carries the credential.
   *
   * @param name the name of the header, query parameter or cookie that carries the API key
   * @param location where the API key is transported on the request
   */
  record ApiKey(String name, Location location) implements SecurityScheme {

    /**
     * Transport location for an {@link ApiKey} credential, mirroring the OpenAPI {@code in}
     * property of an {@code apiKey} security scheme.
     */
    public enum Location {
      /** Credential is sent as an HTTP request header. */
      HEADER,
      /** Credential is sent as a URL query parameter. */
      QUERY,
      /** Credential is sent as an HTTP cookie. */
      COOKIE
    }
  }

  /**
   * OpenAPI {@code type: http} security scheme with {@code scheme: bearer}.
   *
   * <p>Represents bearer-token authentication (typically {@code Authorization: Bearer
   * &lt;token&gt;}). The optional {@code bearerFormat} is a free-form hint from the spec (e.g.
   * {@code JWT}) and is informational only.
   *
   * @param bearerFormat optional OpenAPI {@code bearerFormat} hint describing the token format
   */
  record HttpBearer(Optional<String> bearerFormat) implements SecurityScheme {}

  /**
   * OpenAPI {@code type: http} security scheme with {@code scheme: basic}.
   *
   * <p>Represents HTTP Basic authentication as defined by RFC 7617. Carries no configuration beyond
   * its type.
   */
  record HttpBasic() implements SecurityScheme {}

  /**
   * Fallback for security scheme types parsed from the spec but not yet supported in v1 (notably
   * {@code oauth2}, {@code openIdConnect} and {@code mutualTLS}).
   *
   * <p>Keeping the original {@code type} string allows callers to surface a meaningful diagnostic
   * (or to layer in custom handling) without rejecting the whole OpenAPI document at parse time.
   *
   * @param type the raw OpenAPI {@code type} string as it appeared in the spec (e.g. {@code
   *     oauth2}, {@code openIdConnect}, {@code mutualTLS})
   */
  record Unsupported(String type) implements SecurityScheme {}
}
