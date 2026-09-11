package com.retailsvc.http.internal;

import static com.retailsvc.http.support.TestCodings.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ContentCoding;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentCodingsTest {

  @Test
  void gzipIsTheOnlyCodingWhenNoneAreRegistered() {
    ContentCodings codings = ContentCodings.of(List.of(), List.of());

    assertThat(codings.encoders()).extracting(ContentCoding::token).containsExactly("gzip");
    assertThat(codings.decoders()).containsOnlyKeys("gzip", "x-gzip");
  }

  @Test
  void registeredEncodersArePreferredOverGzipInRegistrationOrder() {
    ContentCodings codings = ContentCodings.of(List.of(), List.of(named("zstd"), named("br")));

    assertThat(codings.encoders())
        .extracting(ContentCoding::token)
        .containsExactly("zstd", "br", "gzip");
  }

  @Test
  void decodersAreKeyedByTokenAndAlias() {
    ContentCoding deflate = named("deflate", "x-deflate");

    ContentCodings codings = ContentCodings.of(List.of(deflate), List.of());

    assertThat(codings.decoders())
        .containsEntry("deflate", deflate)
        .containsEntry("x-deflate", deflate);
  }

  @Test
  void requestAndResponseRegistrationsAreIndependent() {
    ContentCodings codings = ContentCodings.of(List.of(named("br")), List.of(named("zstd")));

    assertThat(codings.decoders()).containsKey("br").doesNotContainKey("zstd");
    assertThat(codings.encoders()).extracting(ContentCoding::token).containsExactly("zstd", "gzip");
  }

  @ParameterizedTest
  @ValueSource(strings = {"zstd", "br", "x-custom.v2", "a1"})
  void acceptsLowerCaseRfc9110Tokens(String token) {
    ContentCoding coding = named(token);

    assertThatCode(() -> ContentCodings.requireRegistrable(coding, List.of()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "zs td",
        "zs,td",
        "zs;td",
        "zstd\r\nX-Injected: yes",
        "ZSTD",
        "zs\"td",
        "zs/td"
      })
  void rejectsTokensThatAreNotLowerCaseRfc9110Tokens(String token) {
    ContentCoding coding = named(token);

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(coding, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"gzip", "x-gzip", "identity", "*"})
  void rejectsReservedTokens(String token) {
    ContentCoding coding = named(token);

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(coding, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(token);
  }

  @Test
  void rejectsReservedAlias() {
    ContentCoding aliasedToGzip = named("fastgzip", "gzip");

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(aliasedToGzip, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullCoding() {
    assertThatThrownBy(() -> ContentCodings.requireRegistrable(null, List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNullToken() {
    ContentCoding nameless = named(null);

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(nameless, List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsATokenAlreadyRegistered() {
    List<ContentCoding> registered = List.of(named("zstd"));
    ContentCoding duplicate = named("zstd");

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(duplicate, registered))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zstd");
  }

  @Test
  void rejectsAnAliasThatCollidesWithARegisteredToken() {
    List<ContentCoding> registered = List.of(named("br"));
    ContentCoding brotli = named("brotli", "br");

    assertThatThrownBy(() -> ContentCodings.requireRegistrable(brotli, registered))
        .isInstanceOf(IllegalStateException.class);
  }
}
