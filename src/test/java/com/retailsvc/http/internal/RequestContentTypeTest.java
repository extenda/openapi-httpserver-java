package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.MediaType;
import com.retailsvc.http.spec.schema.AlwaysSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestContentTypeTest {

  @Test
  void exactMatchWins() {
    var match = RequestContentType.match("application/json", content("*/*", "application/json"));

    assertThat(match)
        .get()
        .extracting(RequestContentType.Match::declared)
        .isEqualTo("application/json");
  }

  @Test
  void typeRangeBeatsAnyType() {
    var match = RequestContentType.match("text/csv", content("*/*", "text/*"));

    assertThat(match).get().extracting(RequestContentType.Match::declared).isEqualTo("text/*");
  }

  @Test
  void anyTypeMatchesWhatNothingElseDoes() {
    var match = RequestContentType.match("image/png", content("text/*", "*/*"));

    assertThat(match).get().extracting(RequestContentType.Match::declared).isEqualTo("*/*");
  }

  @Test
  void parametersAreIgnoredAndCaseDoesNotMatter() {
    var match = RequestContentType.match("Text/Plain; charset=UTF-8", content("TEXT/PLAIN"));

    assertThat(match)
        .get()
        .satisfies(
            m -> {
              assertThat(m.mediaType()).isEqualTo("text/plain");
              assertThat(m.declared()).isEqualTo("TEXT/PLAIN");
            });
  }

  @Test
  void undeclaredTypeDoesNotMatch() {
    assertThat(RequestContentType.match("text/plain", content("application/json"))).isEmpty();
  }

  @Test
  void missingHeaderIsJsonWhenJsonIsDeclared() {
    var declared = content("application/json", "application/octet-stream");

    assertThat(RequestContentType.mediaType(null, declared)).isEqualTo("application/json");
    assertThat(RequestContentType.match(null, declared))
        .get()
        .extracting(RequestContentType.Match::declared)
        .isEqualTo("application/json");
  }

  @Test
  void missingHeaderIsOctetStreamOtherwise() {
    assertThat(RequestContentType.match(null, content("application/octet-stream")))
        .get()
        .extracting(RequestContentType.Match::mediaType)
        .isEqualTo("application/octet-stream");
    assertThat(RequestContentType.match(null, content("*/*"))).isPresent();
    assertThat(RequestContentType.match(null, content("text/plain"))).isEmpty();
  }

  private static Map<String, MediaType> content(String... mediaTypes) {
    Map<String, MediaType> content = new LinkedHashMap<>();
    for (String mediaType : mediaTypes) {
      content.put(mediaType, new MediaType(new AlwaysSchema(Map.of())));
    }
    return content;
  }
}
