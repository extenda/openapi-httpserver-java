package com.retailsvc.http;

import java.util.Optional;

/**
 * Consumer-provided callback that validates an extracted {@link Credential}. Return a non-empty
 * {@link Optional} carrying the principal on success, or {@link Optional#empty()} to deny.
 */
@FunctionalInterface
public interface SchemeValidator {
  Optional<Object> validate(Request request, Credential credential);
}
