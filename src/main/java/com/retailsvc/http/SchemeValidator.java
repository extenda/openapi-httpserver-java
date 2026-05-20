package com.retailsvc.http;

import java.util.Optional;

/**
 * Consumer-provided callback that validates an extracted {@link Credential}. Return a non-empty
 * {@link Optional} carrying the principal on success, or {@link Optional#empty()} to deny.
 */
@FunctionalInterface
public interface SchemeValidator {
  /**
   * Validates the extracted credential for the given request.
   *
   * @param request the incoming request
   * @param credential the credential extracted from the request
   * @return the authenticated principal, or {@link Optional#empty()} to deny
   */
  Optional<Object> validate(Request request, Credential credential);
}
