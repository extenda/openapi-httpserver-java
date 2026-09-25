package com.retailsvc.http.internal;

import static com.retailsvc.http.support.TestCodings.named;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.ContentCoding;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcceptEncodingHeaderTest {

  private static final ContentCoding GZIP = new GzipCoding();
  private static final ContentCoding ZSTD = named("zstd");
  private static final List<ContentCoding> ZSTD_THEN_GZIP = List.of(ZSTD, GZIP);

  @Test
  void nullHeaderIsNotAccepted() {
    assertThat(accepts(null)).isFalse();
  }

  @Test
  void blankHeaderIsNotAccepted() {
    assertThat(accepts("   ")).isFalse();
  }

  @Test
  void plainGzipIsAccepted() {
    assertThat(accepts("gzip")).isTrue();
  }

  @Test
  void gzipAmongOtherCodingsIsAccepted() {
    assertThat(accepts("br, deflate, gzip")).isTrue();
  }

  @Test
  void caseInsensitiveGzipIsAccepted() {
    assertThat(accepts("GZip")).isTrue();
  }

  @Test
  void xGzipIsAccepted() {
    assertThat(accepts("x-gzip")).isTrue();
  }

  @Test
  void explicitZeroQValueIsRefused() {
    assertThat(accepts("gzip;q=0")).isFalse();
    assertThat(accepts("gzip;q=0.0")).isFalse();
  }

  @Test
  void positiveQValueIsAccepted() {
    assertThat(accepts("gzip;q=0.5")).isTrue();
  }

  @Test
  void wildcardIsAccepted() {
    assertThat(accepts("*")).isTrue();
  }

  @Test
  void wildcardWithZeroQValueIsRefused() {
    assertThat(accepts("*;q=0")).isFalse();
  }

  @Test
  void explicitGzipBeatsWildcardRefusal() {
    assertThat(accepts("gzip, *;q=0")).isTrue();
  }

  @Test
  void explicitGzipRefusalBeatsWildcard() {
    assertThat(accepts("gzip;q=0, *")).isFalse();
  }

  @Test
  void identityOnlyIsNotAccepted() {
    assertThat(accepts("identity")).isFalse();
  }

  @Test
  void deflateOnlyIsNotAccepted() {
    assertThat(accepts("deflate, br")).isFalse();
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertThat(accepts("  deflate ,  gzip ; q=0.8 ")).isTrue();
  }

  @Test
  void malformedQValueIsTreatedAsAccepted() {
    assertThat(accepts("gzip;q=bogus")).isTrue();
  }

  @Test
  void emptyTokensAreIgnored() {
    assertThat(accepts("deflate,,gzip")).isTrue();
  }

  @Test
  void repeatedGzipTokensTakeThePositiveWeight() {
    assertThat(accepts("gzip;q=0, gzip")).isTrue();
    assertThat(accepts("gzip, x-gzip;q=0")).isTrue();
  }

  @Test
  void repeatedWildcardsTakeThePositiveWeight() {
    assertThat(accepts("*;q=0, *")).isTrue();
  }

  @Test
  void parametersOtherThanWeightAreIgnored() {
    assertThat(accepts("gzip;level=9")).isTrue();
    assertThat(accepts("gzip;level=9;q=0")).isFalse();
  }

  @Test
  void valuelessParameterIsIgnored() {
    assertThat(accepts("gzip;q")).isTrue();
  }

  // -- choosing among several codings --

  @Test
  void clientWeightOutranksServerPreference() {
    assertThat(AcceptEncodingHeader.select("gzip;q=1.0, zstd;q=0.5", ZSTD_THEN_GZIP))
        .contains(GZIP);
  }

  @Test
  void equalWeightsGoToTheServersFirstChoice() {
    assertThat(AcceptEncodingHeader.select("gzip, zstd", ZSTD_THEN_GZIP)).contains(ZSTD);
  }

  @Test
  void wildcardGoesToTheServersFirstChoice() {
    assertThat(AcceptEncodingHeader.select("*", ZSTD_THEN_GZIP)).contains(ZSTD);
  }

  @Test
  void refusingTheFirstChoiceFallsBackToTheNext() {
    assertThat(AcceptEncodingHeader.select("zstd;q=0, gzip", ZSTD_THEN_GZIP)).contains(GZIP);
  }

  @Test
  void aliasOfALaterChoiceIsHonoured() {
    assertThat(AcceptEncodingHeader.select("x-gzip", ZSTD_THEN_GZIP)).contains(GZIP);
  }

  @Test
  void noSupportedCodingSelectsNothing() {
    assertThat(AcceptEncodingHeader.select("br, deflate", ZSTD_THEN_GZIP)).isEmpty();
  }

  @Test
  void noCandidatesSelectsNothing() {
    assertThat(AcceptEncodingHeader.select("gzip", List.of())).isEmpty();
  }

  private static boolean accepts(String header) {
    return AcceptEncodingHeader.select(header, List.of(GZIP)).isPresent();
  }
}
