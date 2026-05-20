package com.retailsvc.http;

import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by user handlers to signal a 4xx client error. The default {@link ExceptionHandler}
 * renders this as an RFC 7807 {@code application/problem+json} response carrying the supplied
 * status, detail, and optional JSON-pointer / validation-keyword fields.
 *
 * <p>Use for cases like {@code 422 Unprocessable Content} (payload is syntactically valid but
 * violates a business rule), {@code 409 Conflict}, {@code 412 Precondition Failed}, etc. For 5xx
 * errors, throw an ordinary {@link RuntimeException} and let the default handler render 500.
 */
public final class BadRequestException extends RuntimeException {

  private static final int DEFAULT_STATUS = 400;

  /** HTTP 4xx status code carried by this exception. */
  private final int status;

  /** JSON Pointer (RFC 6901) to the offending property; may be {@code null}. */
  private final String pointer;

  /** Validation keyword that failed (for example {@code "required"}); may be {@code null}. */
  private final String keyword;

  /**
   * Creates a new exception with the default HTTP status {@code 400 Bad Request}.
   *
   * <p>Equivalent to {@link #BadRequestException(int, String, String, String)} invoked with {@code
   * status = 400} and no pointer or keyword.
   *
   * @param detail human-readable explanation of the error; surfaced as the RFC 7807 {@code detail}
   *     member
   */
  public BadRequestException(String detail) {
    this(DEFAULT_STATUS, detail, null, null);
  }

  /**
   * Creates a new exception with an explicit 4xx HTTP status.
   *
   * <p>Equivalent to {@link #BadRequestException(int, String, String, String)} with no pointer or
   * keyword.
   *
   * @param status the HTTP status code; must be in the range {@code 400}-{@code 499} or an {@link
   *     IllegalArgumentException} is thrown
   * @param detail human-readable explanation of the error; surfaced as the RFC 7807 {@code detail}
   *     member
   */
  public BadRequestException(int status, String detail) {
    this(status, detail, null, null);
  }

  /**
   * Creates a new exception with the full set of RFC 7807 problem fields. Intended for
   * validation-style errors where the offending property can be pinpointed with a JSON Pointer and
   * the failing rule identified by a JSON Schema keyword.
   *
   * @param status the HTTP status code; must be in the range {@code 400}-{@code 499}
   * @param detail human-readable explanation of the error; surfaced as the RFC 7807 {@code detail}
   *     member
   * @param pointer JSON Pointer (RFC 6901) to the offending property in the request payload; may be
   *     {@code null} when not applicable
   * @param keyword JSON Schema / validation keyword that failed (for example {@code "required"},
   *     {@code "pattern"}, {@code "maxLength"}); may be {@code null} when not applicable
   * @throws NullPointerException if {@code detail} is {@code null}
   * @throws IllegalArgumentException if {@code status} is not in the range {@code 400}-{@code 499}
   */
  public BadRequestException(int status, String detail, String pointer, String keyword) {
    super(Objects.requireNonNull(detail, "detail must not be null"));
    if (status < 400 || status > 499) {
      throw new IllegalArgumentException("status must be 4xx, got " + status);
    }
    this.status = status;
    this.pointer = pointer;
    this.keyword = keyword;
  }

  /**
   * Returns the HTTP status code carried by this exception.
   *
   * @return the HTTP status code; always a 4xx value in the range {@code 400}-{@code 499}
   */
  public int status() {
    return status;
  }

  /**
   * Returns the JSON Pointer locating the offending property in the request payload.
   *
   * @return an {@link Optional} containing the JSON Pointer (RFC 6901) to the offending property,
   *     or {@link Optional#empty()} when no pointer was supplied
   */
  public Optional<String> pointer() {
    return Optional.ofNullable(pointer);
  }

  /**
   * Returns the JSON Schema / validation keyword that failed.
   *
   * @return an {@link Optional} containing the failing JSON Schema or validation keyword (for
   *     example {@code "required"}, {@code "pattern"}), or {@link Optional#empty()} when no keyword
   *     was supplied
   */
  public Optional<String> keyword() {
    return Optional.ofNullable(keyword);
  }
}
