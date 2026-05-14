package com.retailsvc.http.internal.gson;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GsonJsonMapperTest {

  private final GsonJsonMapper mapper = new GsonJsonMapper();

  @Test
  void readPreservesIntegersAsLong() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) mapper.readFrom(bytes("{\"n\":42}"), "application/json");
    assertThat(parsed.get("n")).isEqualTo(42L).isInstanceOf(Long.class);
  }

  @Test
  void readKeepsFractionalAsDouble() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) mapper.readFrom(bytes("{\"n\":1.5}"), "application/json");
    assertThat(parsed.get("n")).isEqualTo(1.5).isInstanceOf(Double.class);
  }

  @Test
  void readBasicTypes() {
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>)
            mapper.readFrom(
                bytes("{\"s\":\"hi\",\"b\":true,\"n\":null,\"a\":[1,2]}"), "application/json");
    assertThat(parsed)
        .containsEntry("s", "hi")
        .containsEntry("b", Boolean.TRUE)
        .containsEntry("n", null)
        .containsEntry("a", List.of(1L, 2L));
  }

  @Test
  void writesMapAndList() {
    byte[] out = mapper.writeTo(Map.of("k", List.of(1L, 2L)));
    assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("{\"k\":[1,2]}");
  }

  @Test
  void writesInstantAsIso8601() {
    Instant t = Instant.parse("2026-05-13T10:00:00Z");
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00:00Z\"}");
  }

  @Test
  void writesOffsetDateTimeAsIso8601() {
    OffsetDateTime t = OffsetDateTime.of(2026, 5, 13, 10, 0, 0, 0, ZoneOffset.UTC);
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00Z\"}");
  }

  @Test
  void writesZonedDateTimeAsIso8601() {
    ZonedDateTime t = ZonedDateTime.of(2026, 5, 13, 10, 0, 0, 0, ZoneOffset.UTC);
    assertThat(new String(mapper.writeTo(Map.of("ts", t)), StandardCharsets.UTF_8))
        .contains("2026-05-13T10:00Z");
  }

  @Test
  void writesLocalDateTimeAsIso8601() {
    assertThat(
            new String(
                mapper.writeTo(Map.of("ts", LocalDateTime.of(2026, 5, 13, 10, 0))),
                StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00\"}");
  }

  @Test
  void writesLocalDateAsIso8601() {
    assertThat(
            new String(
                mapper.writeTo(Map.of("d", LocalDate.of(2026, 5, 13))), StandardCharsets.UTF_8))
        .isEqualTo("{\"d\":\"2026-05-13\"}");
  }

  @Test
  void writesLocalTimeAsIso8601() {
    assertThat(new String(mapper.writeTo(Map.of("t", LocalTime.of(10, 0))), StandardCharsets.UTF_8))
        .isEqualTo("{\"t\":\"10:00\"}");
  }

  @Test
  void readAsDeserialisesPojo() {
    Item item = mapper.readAs(bytes("{\"id\":\"x-1\",\"qty\":7}"), "application/json", Item.class);

    assertThat(item.id).isEqualTo("x-1");
    assertThat(item.qty).isEqualTo(7);
  }

  @Test
  void readAsRoundTripsJsr310Fields() {
    WithDates value =
        mapper.readAs(
            bytes("{\"ts\":\"2026-05-13T10:00:00Z\",\"day\":\"2026-05-13\"}"),
            "application/json",
            WithDates.class);

    assertThat(value.ts).isEqualTo(Instant.parse("2026-05-13T10:00:00Z"));
    assertThat(value.day).isEqualTo(LocalDate.of(2026, 5, 13));
  }

  static final class Item {
    String id;
    int qty;
  }

  static final class WithDates {
    Instant ts;
    LocalDate day;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
