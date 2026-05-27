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
    super(Objects.requireNonNull(detail, "detail must not be null"), cause);
    if (status < 400 || status > 499) {
      throw new IllegalArgumentException("status must be 4xx, got " + status);
    }
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
