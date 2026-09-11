package com.retailsvc.http;

import static com.retailsvc.http.support.TestCodings.deflate;
import static com.retailsvc.http.support.TestCodings.named;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiServerBuilderTest {

  private final Spec spec = testSpec();

  @Test
  void buildsWithRequiredFieldsOnly() {
    assertDoesNotThrow(
        () -> {
          try (var _ = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build()) {
            // close on exit
          }
        });
  }

  @Test
  void rejectsDuplicateExtraPathOnSecondAddHandler() {
    RequestHandler duplicate = req -> Response.empty();
    OpenApiServer.Builder b =
        OpenApiServer.builder().spec(spec).handlers(emptyMap()).extraRoute("/alive", duplicate);

    assertThatThrownBy(() -> b.extraRoute("/alive", duplicate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/alive");
  }

  @Test
  void rejectsExtraPathEqualToSpecBasePathAtBuildTime() {
    // testSpec() uses "/api" as the basePath (servers[0].url = http://localhost:8080/api).
    OpenApiServer.Builder b =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(emptyMap())
            .extraRoute("/api", Handlers.aliveHandler())
            .port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("/api");
  }

  @Test
  void rejectsNonPositiveMaxDecompressedRequestBytes() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThatThrownBy(() -> b.maxDecompressedRequestBytes(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0");
    assertThatThrownBy(() -> b.maxDecompressedRequestBytes(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-1");
  }

  @Test
  void rejectsOversizedMaxDecompressedRequestBytes() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThatThrownBy(() -> b.maxDecompressedRequestBytes(Integer.MAX_VALUE + 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeMinCompressibleResponseBytes() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThatThrownBy(() -> b.minCompressibleResponseBytes(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-1");
  }

  @Test
  void acceptsContentCodingLimits() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThat(b.maxDecompressedRequestBytes(4096).minCompressibleResponseBytes(0)).isSameAs(b);
  }

  @Test
  void contentCodingRejectsTheBuiltInGzip() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    ContentCoding gzip = named("gzip");

    assertThatThrownBy(() -> b.contentCoding(gzip))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gzip");
  }

  @Test
  void contentCodingRejectsATokenRegisteredTwice() {
    OpenApiServer.Builder b = OpenApiServer.builder().contentCoding(deflate());
    ContentCoding again = deflate();

    assertThatThrownBy(() -> b.contentCoding(again))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("deflate");
  }

  @Test
  void oneCodingMayBeRegisteredOncePerDirection() {
    ContentCoding deflate = deflate();
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThat(b.requestContentCoding(deflate).responseContentCoding(deflate)).isSameAs(b);
  }

  @Test
  void contentCodingConflictsWithAnEarlierOneWayRegistration() {
    OpenApiServer.Builder b = OpenApiServer.builder().responseContentCoding(deflate());
    ContentCoding bothWays = deflate();

    assertThatThrownBy(() -> b.contentCoding(bothWays)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsNegativeShutdownTimeout() {
    OpenApiServer.Builder b = OpenApiServer.builder();

    assertThatThrownBy(() -> b.shutdownTimeoutSeconds(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-1");
  }

  @Test
  void buildsWithShutdownTimeout() {
    assertDoesNotThrow(
        () -> {
          try (var _ =
              OpenApiServer.builder()
                  .spec(spec)
                  .handlers(emptyMap())
                  .port(0)
                  .shutdownTimeoutSeconds(2)
                  .build()) {
            // close on exit drains for up to 2s (no in-flight exchanges, so returns immediately)
          }
        });
  }

  @Test
  void stopRejectsNegativeDelay() throws Exception {
    try (var s = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build()) {

      assertThatThrownBy(() -> s.stop(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("-1");
    }
  }

  @Test
  void stopWithZeroSucceeds() throws Exception {
    var s = OpenApiServer.builder().spec(spec).handlers(emptyMap()).port(0).build();
    assertDoesNotThrow(() -> s.stop(0));
  }

  @Test
  void rejectsNullSpec() {
    OpenApiServer.Builder b = OpenApiServer.builder().handlers(emptyMap()).port(0);

    assertThatThrownBy(b::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Spec");
  }

  @Test
  void bodyMapperRejectsNullMediaType() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    TypeMapper noopMapper =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] body, String contentTypeHeader) {
            return null;
          }

          @Override
          public byte[] writeTo(Object value) {
            return new byte[0];
          }
        };
    assertThatThrownBy(() -> b.bodyMapper(null, noopMapper))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bodyMapperRejectsNullMapper() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    assertThatThrownBy(() -> b.bodyMapper("application/json", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void afterResponseHookRejectsNull() {
    OpenApiServer.Builder b = OpenApiServer.builder();
    assertThatThrownBy(() -> b.afterResponseHook(null)).isInstanceOf(NullPointerException.class);
  }

  private static Spec testSpec() {
    Map<String, Object> raw =
        Map.of(
            "openapi", "3.1.0",
            "info", Map.of("title", "Test API", "version", "1.0"),
            "servers", List.of(Map.of("url", "http://localhost:8080/api")),
            "paths", emptyMap());
    return Spec.from(raw);
  }
}
