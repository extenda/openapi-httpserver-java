package com.retailsvc.http.support;

import com.google.gson.Gson;
import com.retailsvc.http.spec.Spec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only helper: loads {@code /openapi.json} from the classpath, deep-clones the parsed map, and
 * re-points {@code servers[0].url} so callers can derive multiple {@link Spec} instances from a
 * single fixture file.
 */
public final class SpecFixtures {

  private SpecFixtures() {}

  /** Loads the test spec and rewrites {@code servers[0].url} to {@code newServerUrl}. */
  public static Spec specAt(String newServerUrl) {
    Map<String, Object> raw = readBaseSpec();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> servers = (List<Map<String, Object>>) raw.get("servers");
    if (servers == null || servers.isEmpty()) {
      throw new IllegalStateException("test spec has no servers entry");
    }
    servers.get(0).put("url", newServerUrl);
    return Spec.from(raw);
  }

  private static Map<String, Object> readBaseSpec() {
    try (InputStream in = SpecFixtures.class.getResourceAsStream("/openapi.json")) {
      if (in == null) {
        throw new IllegalStateException("/openapi.json not found on test classpath");
      }
      return parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(InputStream in) {
    Gson gson = new Gson();
    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      Map<String, Object> root = gson.fromJson(reader, Map.class);
      return deepClone(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Object> deepClone(Map<String, Object> in) {
    Map<String, Object> out = new LinkedHashMap<>(in.size());
    for (var e : in.entrySet()) {
      out.put(e.getKey(), cloneValue(e.getValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object cloneValue(Object v) {
    if (v instanceof Map<?, ?> m) {
      return deepClone((Map<String, Object>) m);
    }
    if (v instanceof List<?> l) {
      List<Object> out = new ArrayList<>(l.size());
      for (Object item : l) {
        out.add(cloneValue(item));
      }
      return out;
    }
    return v;
  }
}
