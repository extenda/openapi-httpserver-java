package com.retailsvc.http.validate;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.Schema;

/**
 * Validator contract: validates a value against a {@link Schema}, throwing {@link
 * ValidationException} on the first failure.
 *
 * <p>{@link DefaultValidator} is the library-provided implementation.
 */
public interface Validator {
  /**
   * Validates {@code value} against {@code schema}, throwing {@link ValidationException} on the
   * first failure.
   *
   * @param value the value to validate (may be {@code null} — a {@code null} is accepted only when
   *     the schema permits the {@code null} type)
   * @param schema the schema to validate against
   * @param pointer JSON Pointer prefix used to qualify the path of any failure (use empty string
   *     for root)
   * @throws ValidationException on the first failure
   */
  void validate(Object value, Schema schema, String pointer);
}
