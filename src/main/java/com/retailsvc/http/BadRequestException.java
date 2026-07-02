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

  private static final int MIN_CLIENT_ERROR_STATUS = 400;
  private static final int MAX_CLIENT_ERROR_STATUS = 499;
  private static final int DEFAULT_STATUS = MIN_CLIENT_ERROR_STATUS;

  private final int status;
  private final String pointer;
  private final String keyword;

  public BadRequestException(String detail) {
    this(DEFAULT_STATUS, detail, null, null, null);
  }

  public BadRequestException(String detail, Throwable cause) {
    this(DEFAULT_STATUS, detail, null, null, cause);
  }

  public BadRequestException(int status, String detail) {
    this(status, detail, null, null, null);
  }

  public BadRequestException(int status, String detail, Throwable cause) {
    this(status, detail, null, null, cause);
  }

  public BadRequestException(int status, String detail, String pointer, String keyword) {
    this(status, detail, pointer, keyword, null);
  }

  public BadRequestException(
      int status, String detail, String pointer, String keyword, Throwable cause) {
    if (status < MIN_CLIENT_ERROR_STATUS || status > MAX_CLIENT_ERROR_STATUS) {
      throw new IllegalArgumentException("status must be 4xx, got " + status);
    }
    super(Objects.requireNonNull(detail, "detail must not be null"), cause);
    this.status = status;
    this.pointer = pointer;
    this.keyword = keyword;
  }

  public int status() {
    return status;
  }

  public Optional<String> pointer() {
    return Optional.ofNullable(pointer);
  }

  public Optional<String> keyword() {
    return Optional.ofNullable(keyword);
  }
}
