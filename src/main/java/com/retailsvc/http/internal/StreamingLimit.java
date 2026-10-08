package com.retailsvc.http.internal;

import com.retailsvc.http.Response;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Caps how many streaming requests reach their handler at once. A request over the cap is answered
 * {@code 503 Service Unavailable} with {@code Retry-After}, with its body left unread. Shared by
 * every spec binding and the extra routes, so the cap is server-wide. Thread-safe.
 */
public final class StreamingLimit {

  /** No cap: every request is admitted. */
  public static final StreamingLimit UNLIMITED = new StreamingLimit(null, Duration.ZERO);

  private static final int SERVICE_UNAVAILABLE = 503;

  private final Semaphore permits;
  private final String retryAfterSeconds;

  private StreamingLimit(Semaphore permits, Duration retryAfter) {
    this.permits = permits;
    this.retryAfterSeconds = Long.toString(ceilSeconds(retryAfter));
  }

  /**
   * A cap of {@code maxConcurrent} streaming requests; rejections ask the client to retry after
   * {@code retryAfter}, rounded up to whole seconds.
   */
  public static StreamingLimit of(int maxConcurrent, Duration retryAfter) {
    if (maxConcurrent <= 0) {
      throw new IllegalArgumentException("maxConcurrent must be positive, got " + maxConcurrent);
    }
    if (retryAfter.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative, got " + retryAfter);
    }
    return new StreamingLimit(new Semaphore(maxConcurrent), retryAfter);
  }

  /**
   * Admits a request if a slot is free, without waiting. Pair a {@code true} with {@link #release}.
   */
  public boolean tryAcquire() {
    return permits == null || permits.tryAcquire();
  }

  /** Frees the slot taken by a successful {@link #tryAcquire()}. */
  public void release() {
    if (permits != null) {
      permits.release();
    }
  }

  /** The {@code 503} a request over the cap gets, as an RFC 9457 problem. */
  public Response rejection() {
    ProblemDetail problem =
        new ProblemDetail(
            "about:blank",
            "Service Unavailable",
            SERVICE_UNAVAILABLE,
            "too many concurrent streaming requests",
            List.of());
    return Response.bytes(
            SERVICE_UNAVAILABLE,
            ProblemDetailRenderer.renderJson(problem),
            "application/problem+json")
        .withHeader("Retry-After", retryAfterSeconds);
  }

  private static long ceilSeconds(Duration duration) {
    long seconds = duration.getSeconds();
    return duration.getNano() > 0 ? seconds + 1 : seconds;
  }
}
