package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BadRequestExceptionTest {

  @Test
  void defaultsStatusTo400() {
    BadRequestException e = new BadRequestException("bad input");

    assertThat(e.status()).isEqualTo(400);
    assertThat(e.getMessage()).isEqualTo("bad input");
    assertThat(e.pointer()).isEmpty();
    assertThat(e.keyword()).isEmpty();
  }

  @Test
  void honorsExplicitStatus() {
    BadRequestException e = new BadRequestException(422, "email taken");

    assertThat(e.status()).isEqualTo(422);
    assertThat(e.getMessage()).isEqualTo("email taken");
  }

  @Test
  void carriesPointerAndKeyword() {
    BadRequestException e = new BadRequestException(422, "email taken", "/email", "unique");

    assertThat(e.pointer()).contains("/email");
    assertThat(e.keyword()).contains("unique");
  }

  @Test
  void rejectsNon4xxStatus() {
    assertThatThrownBy(() -> new BadRequestException(500, "boom"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4xx");
    assertThatThrownBy(() -> new BadRequestException(399, "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("4xx");
  }

  @Test
  void rejectsNullDetail() {
    assertThatThrownBy(() -> new BadRequestException(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("detail");
  }
}
