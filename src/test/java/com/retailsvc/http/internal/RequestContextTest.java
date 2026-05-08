package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContextTest {

  private static final byte[] BODY_A = {1, 2, 3};
  private static final byte[] BODY_A_COPY = {1, 2, 3};
  private static final byte[] BODY_B = {1, 2, 4};

  private RequestContext context(byte[] body, Object parsed, String opId, Map<String, String> pp) {
    return new RequestContext(body, parsed, opId, pp);
  }

  @Test
  void equalsIsReflexive() {
    RequestContext c = context(BODY_A, "p", "op", Map.of("k", "v"));
    assertThat(c).isEqualTo(c);
  }

  @Test
  void equalsTreatsByteArraysStructurally() {
    RequestContext a = context(BODY_A, "p", "op", Map.of("k", "v"));
    RequestContext b = context(BODY_A_COPY, "p", "op", Map.of("k", "v"));
    assertThat(a).isEqualTo(b);
    assertThat(a).hasSameHashCodeAs(b);
  }

  @Test
  void equalsRejectsDifferentBytes() {
    RequestContext a = context(BODY_A, "p", "op", Map.of("k", "v"));
    RequestContext b = context(BODY_B, "p", "op", Map.of("k", "v"));
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsRejectsDifferentParsedBody() {
    RequestContext a = context(BODY_A, "p", "op", Map.of("k", "v"));
    RequestContext b = context(BODY_A, "different", "op", Map.of("k", "v"));
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsRejectsDifferentOperationId() {
    RequestContext a = context(BODY_A, "p", "op", Map.of("k", "v"));
    RequestContext b = context(BODY_A, "p", "other-op", Map.of("k", "v"));
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsRejectsDifferentPathParameters() {
    RequestContext a = context(BODY_A, "p", "op", Map.of("k", "v"));
    RequestContext b = context(BODY_A, "p", "op", Map.of("k", "other"));
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsRejectsNullAndOtherTypes() {
    RequestContext c = context(BODY_A, "p", "op", Map.of());
    assertThat(c).isNotEqualTo(null);
    assertThat(c).isNotEqualTo("not a context");
  }

  @Test
  void equalsHandlesNullParsedBody() {
    RequestContext a = context(BODY_A, null, "op", Map.of());
    RequestContext b = context(BODY_A_COPY, null, "op", Map.of());
    assertThat(a).isEqualTo(b);
  }

  @Test
  void hashCodeIsStableAcrossInvocations() {
    RequestContext c = context(BODY_A, "p", "op", Map.of("k", "v"));
    int first = c.hashCode();
    int second = c.hashCode();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void toStringSummarisesBodyByLength() {
    RequestContext c = context(BODY_A, "parsed", "get-x", Map.of("id", "42"));
    assertThat(c.toString())
        .contains("body=byte[3]")
        .contains("parsedBody=parsed")
        .contains("operationId=get-x")
        .contains("pathParameters={id=42}")
        .doesNotContain("[B@");
  }

  @Test
  void toStringHandlesNullBody() {
    RequestContext c = context(null, null, "op", Map.of());
    assertThat(c.toString()).contains("body=byte[0]");
  }
}
