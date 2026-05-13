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
import com.google.gson.stream.JsonWriter;
import com.retailsvc.http.TypeMapper;
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

/**
 * Built-in {@link TypeMapper} for {@code application/json} backed by Gson. Auto-registered by
 * {@link com.retailsvc.http.OpenApiServer.Builder} when Gson is on the classpath and no
 * user-supplied JSON mapper has been registered.
 *
 * <p>JSON numbers without a decimal point or exponent are returned as {@code Long}; fractional
 * numbers are returned as {@code Double}. JSR-310 types ({@code Instant}, {@code OffsetDateTime},
 * {@code ZonedDateTime}, {@code LocalDateTime}, {@code LocalDate}, {@code LocalTime}) are written
 * as their ISO-8601 string form.
 */
public final class GsonJsonMapper implements TypeMapper {

  private final Gson gson;

  public GsonJsonMapper() {
    this.gson =
        new GsonBuilder()
            .registerTypeAdapter(Instant.class, isoStringWriter(Instant::toString))
            .registerTypeAdapter(OffsetDateTime.class, isoStringWriter(OffsetDateTime::toString))
            .registerTypeAdapter(ZonedDateTime.class, isoStringWriter(ZonedDateTime::toString))
            .registerTypeAdapter(LocalDateTime.class, isoStringWriter(LocalDateTime::toString))
            .registerTypeAdapter(LocalDate.class, isoStringWriter(LocalDate::toString))
            .registerTypeAdapter(LocalTime.class, isoStringWriter(LocalTime::toString))
            .create();
  }

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
    return toJavaObject(element);
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
    } else if (element instanceof JsonObject obj) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
        map.put(entry.getKey(), toJavaObject(entry.getValue()));
      }
      return map;
    } else if (element instanceof JsonArray arr) {
      List<Object> list = new ArrayList<>(arr.size());
      for (JsonElement item : arr) {
        list.add(toJavaObject(item));
      }
      return list;
    } else if (element instanceof JsonPrimitive prim) {
      if (prim.isBoolean()) {
        return prim.getAsBoolean();
      } else if (prim.isString()) {
        return prim.getAsString();
      } else {
        // Number
        String raw = prim.getAsString();
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
          try {
            return Long.parseLong(raw);
          } catch (NumberFormatException _) {
            // falls through to Double for out-of-range integers
          }
        }
        return Double.parseDouble(raw);
      }
    }
    throw new IllegalStateException("Unexpected JsonElement type: " + element.getClass());
  }

  private static <T> TypeAdapter<T> isoStringWriter(java.util.function.Function<T, String> toIso) {
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
      public T read(JsonReader in) {
        throw new UnsupportedOperationException(
            "GsonJsonMapper does not parse JSR-310 types; values arrive as String");
      }
    };
  }
}
