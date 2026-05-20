package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GsonTypeMapperTest {

  @Test
  void noArgConstructorRoundTripsInstantAsIso8601() {
    GsonTypeMapper mapper = new GsonTypeMapper();

    byte[] out = mapper.writeTo(Map.of("ts", Instant.parse("2026-05-13T10:00:00Z")));

    assertThat(new String(out, StandardCharsets.UTF_8))
        .isEqualTo("{\"ts\":\"2026-05-13T10:00:00Z\"}");
  }

  @Test
  void readAsDelegatesToWrappedGson() {
    GsonTypeMapper mapper = new GsonTypeMapper();

    Item item =
        mapper.readAs(
            "{\"id\":\"x\",\"qty\":3}".getBytes(StandardCharsets.UTF_8),
            "application/json",
            Item.class);

    assertThat(item.id).isEqualTo("x");
    assertThat(item.qty).isEqualTo(3);
  }

  @Test
  void customGsonIsUsed() {
    Gson custom = new GsonBuilder().serializeNulls().create();
    GsonTypeMapper mapper = new GsonTypeMapper(custom);

    assertThat(
            new String(mapper.writeTo(Collections.singletonMap("k", null)), StandardCharsets.UTF_8))
        .isEqualTo("{\"k\":null}");
  }

  @Test
  void nullGsonRejected() {
    assertThatNullPointerException().isThrownBy(() -> new GsonTypeMapper(null));
  }

  @Test
  void gsonBuilderReturnsBuilderForWrappedGson() {
    Gson custom = new GsonBuilder().serializeNulls().create();
    GsonTypeMapper mapper = new GsonTypeMapper(custom);

    Gson derived = mapper.gsonBuilder().create();

    assertThat(derived.toJson(Collections.singletonMap("k", null))).isEqualTo("{\"k\":null}");
  }

  static final class Item {
    String id;
    int qty;
  }
}
