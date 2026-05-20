package com.retailsvc.http.internal.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.retailsvc.http.TypedTypeMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Built-in {@link TypedTypeMapper} for {@code application/json} backed by Gson. Auto-registered by
 * {@link com.retailsvc.http.OpenApiServer.Builder} when Gson is on the classpath and no
 * user-supplied JSON mapper has been registered.
 *
 * <p>The loose {@link #readFrom(byte[], String)} path returns JSON numbers without a decimal point
 * or exponent as {@code Long} and fractional numbers as {@code Double}. The typed {@link
 * #readAs(byte[], String, Class)} path delegates to Gson, so target-type fields determine the
 * resulting Java types (an {@code int} field gets an {@code int}, an {@code Instant} field gets an
 * {@code Instant}, etc.).
 *
 * <p>JSR-310 types ({@code Instant}, {@code OffsetDateTime}, {@code ZonedDateTime}, {@code
 * LocalDateTime}, {@code LocalDate}, {@code LocalTime}) are round-tripped as their ISO-8601 string
 * form.
 */
public final class GsonJsonMapper implements TypedTypeMapper {

  private final Gson gson;

  /** Creates a mapper backed by a default {@link Gson} instance with JSR-310 adapters. */
  public GsonJsonMapper() {
    this(defaultGson());
  }

  /**
   * Creates a mapper backed by the supplied {@link Gson} instance.
   *
   * @param gson the Gson instance to delegate to; must not be {@code null}
   */
  public GsonJsonMapper(Gson gson) {
    this.gson = Objects.requireNonNull(gson, "gson must not be null");
  }

  /**
   * Returns the wrapped {@link Gson} instance.
   *
   * @return the wrapped {@link Gson} instance
   */
  public Gson gson() {
    return gson;
  }

  private static Gson defaultGson() {
    return new GsonBuilder()
        .registerTypeAdapter(Instant.class, iso(Instant::toString, Instant::parse))
        .registerTypeAdapter(
            OffsetDateTime.class, iso(OffsetDateTime::toString, OffsetDateTime::parse))
        .registerTypeAdapter(
            ZonedDateTime.class, iso(ZonedDateTime::toString, ZonedDateTime::parse))
        .registerTypeAdapter(
            LocalDateTime.class, iso(LocalDateTime::toString, LocalDateTime::parse))
        .registerTypeAdapter(LocalDate.class, iso(LocalDate::toString, LocalDate::parse))
        .registerTypeAdapter(LocalTime.class, iso(LocalTime::toString, LocalTime::parse))
        .create();
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
    return toJavaObject(element);
  }

  @Override
  public <T> T readAs(byte[] body, String contentTypeHeader, Class<T> type) {
    return gson.fromJson(new String(body, StandardCharsets.UTF_8), type);
  }

  @Override
  public byte[] writeTo(Object value) {
    return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Recursively converts a {@link JsonElement} tree to plain Java objects, preserving integers as
   * {@code Long} and fractional numbers as {@code Double}.
   */
  private static Object toJavaObject(JsonElement element) {
    if (element == null || element instanceof JsonNull) {
      return null;
    }
    if (element instanceof JsonObject obj) {
      return toMap(obj);
    }
    if (element instanceof JsonArray arr) {
      return toList(arr);
    }
    if (element instanceof JsonPrimitive prim) {
      return toPrimitive(prim);
    }
    throw new IllegalStateException("Unexpected JsonElement type: " + element.getClass());
  }

  private static Map<String, Object> toMap(JsonObject obj) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
      map.put(entry.getKey(), toJavaObject(entry.getValue()));
    }
    return map;
  }

  private static List<Object> toList(JsonArray arr) {
    List<Object> list = new ArrayList<>(arr.size());
    for (JsonElement item : arr) {
      list.add(toJavaObject(item));
    }
    return list;
  }

  private static Object toPrimitive(JsonPrimitive prim) {
    if (prim.isBoolean()) {
      return prim.getAsBoolean();
    }
    if (prim.isString()) {
      return prim.getAsString();
    }
    return toNumber(prim.getAsString());
  }

  private static Object toNumber(String raw) {
    if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
      try {
        return Long.parseLong(raw);
      } catch (NumberFormatException _) {
        // Falls through to Double for out-of-Long-range integers.
      }
    }
    return Double.parseDouble(raw);
  }

  /** Round-trips a JSR-310 type as an ISO-8601 string. */
  private static <T> TypeAdapter<T> iso(Function<T, String> toIso, Function<String, T> fromIso) {
    return new TypeAdapter<T>() {
      @Override
      public void write(JsonWriter out, T value) throws IOException {
        if (value == null) {
          out.nullValue();
        } else {
          out.value(toIso.apply(value));
        }
      }

      @Override
      public T read(JsonReader in) throws IOException {
        if (in.peek() == JsonToken.NULL) {
          in.nextNull();
          return null;
        }
        return fromIso.apply(in.nextString());
      }
    };
  }
}
