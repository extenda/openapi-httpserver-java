package com.retailsvc.http.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.Response;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamingLimitTest {

  @Test
  void unlimitedAlwaysAdmits() {
    for (int i = 0; i < 1000; i++) {
      assertThat(StreamingLimit.UNLIMITED.tryAcquire()).isTrue();
    }
    StreamingLimit.UNLIMITED.release();
  }

  @Test
  void admitsUpToTheCapAndAgainAfterARelease() {
    StreamingLimit limit = StreamingLimit.of(2, Duration.ofSeconds(1));

    assertThat(limit.tryAcquire()).isTrue();
    assertThat(limit.tryAcquire()).isTrue();
    assertThat(limit.tryAcquire()).isFalse();

    limit.release();

    assertThat(limit.tryAcquire()).isTrue();
  }

  @Test
  void rejectionIsA503ProblemWithRetryAfterRoundedUp() {
    Response response = StreamingLimit.of(1, Duration.ofMillis(1500)).rejection();

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.contentType()).isEqualTo("application/problem+json");
    assertThat(response.headers()).containsEntry("Retry-After", "2");
    assertThat(new String((byte[]) response.body(), UTF_8))
        .contains("\"status\":503")
        .contains("Service Unavailable");
  }

  @Test
  void wholeSecondsAreKept() {
    assertThat(StreamingLimit.of(1, Duration.ofSeconds(5)).rejection().headers())
        .containsEntry("Retry-After", "5");
    assertThat(StreamingLimit.of(1, Duration.ZERO).rejection().headers())
        .containsEntry("Retry-After", "0");
  }

  @Test
  void rejectsInvalidArguments() {
    Duration second = Duration.ofSeconds(1);
    Duration negative = Duration.ofSeconds(-1);
    assertThatThrownBy(() -> StreamingLimit.of(0, second))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StreamingLimit.of(1, negative))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
