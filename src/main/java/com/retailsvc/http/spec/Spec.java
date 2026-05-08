package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.SchemaParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public record Spec(
    String openapi,
    Info info,
    List<Server> servers,
    List<Operation> operations,
    Map<String, Schema> componentSchemas,
    Map<String, Parameter> componentParameters) {

  @SuppressWarnings("unchecked")
  public static Spec from(Map<String, Object> raw) {
    String openapi = (String) raw.get("openapi");
    Info info = parseInfo((Map<String, Object>) raw.get("info"));
    List<Server> servers = parseServers((List<Map<String, Object>>) raw.get("servers"));
    Map<String, Object> rawComponents =
        (Map<String, Object>) raw.getOrDefault("components", Map.of());
    Map<String, Schema> componentSchemas = parseComponentSchemas(rawComponents);
    Map<String, Parameter> componentParameters = parseComponentParameters(rawComponents);
    List<Operation> operations =
        parseOperations(
            (Map<String, Object>) raw.getOrDefault("paths", Map.of()), componentParameters);
    return new Spec(openapi, info, servers, operations, componentSchemas, componentParameters);
  }

  public String basePath() {
    if (servers.isEmpty()) {
      throw new IllegalStateException("no servers declared");
    }
    return Optional.ofNullable(URI.create(servers.get(0).url()).getPath()).orElse("");
  }

  public Schema resolveSchema(String ref) {
    String name = stripPrefix(ref, "#/components/schemas/");
    Schema s = componentSchemas.get(name);
    if (s == null) throw new IllegalArgumentException("unknown schema ref: " + ref);
    return s;
  }

  public Parameter resolveParameter(String ref) {
    String name = stripPrefix(ref, "#/components/parameters/");
    Parameter p = componentParameters.get(name);
    if (p == null) throw new IllegalArgumentException("unknown parameter ref: " + ref);
    return p;
  }

  private static String stripPrefix(String ref, String prefix) {
    if (!ref.startsWith(prefix)) {
      throw new IllegalArgumentException("ref does not start with " + prefix + ": " + ref);
    }
    return ref.substring(prefix.length());
  }

  private static Info parseInfo(Map<String, Object> raw) {
    return new Info((String) raw.get("title"), (String) raw.get("version"));
  }

  private static List<Server> parseServers(List<Map<String, Object>> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    return raw.stream().map(m -> new Server((String) m.get("url"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Schema> parseComponentSchemas(Map<String, Object> rawComponents) {
    Map<String, Object> rawSchemas =
        (Map<String, Object>) rawComponents.getOrDefault("schemas", Map.of());
    Map<String, Schema> out = new LinkedHashMap<>();
    for (var e : rawSchemas.entrySet()) {
      out.put(e.getKey(), SchemaParser.parse((Map<String, Object>) e.getValue()));
    }
    return Map.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Parameter> parseComponentParameters(
      Map<String, Object> rawComponents) {
    Map<String, Object> rawParams =
        (Map<String, Object>) rawComponents.getOrDefault("parameters", Map.of());
    Map<String, Parameter> out = new LinkedHashMap<>();
    for (var e : rawParams.entrySet()) {
      out.put(e.getKey(), parseParameter((Map<String, Object>) e.getValue()));
    }
    return Map.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Parameter parseParameter(Map<String, Object> raw) {
    return new Parameter(
        (String) raw.get("name"),
        Parameter.Location.valueOf(((String) raw.get("in")).toUpperCase(Locale.ROOT)),
        Boolean.TRUE.equals(raw.get("required")),
        SchemaParser.parse(
            (Map<String, Object>) raw.getOrDefault("schema", Map.of("type", "string"))));
  }

  @SuppressWarnings("unchecked")
  private static List<Operation> parseOperations(
      Map<String, Object> rawPaths, Map<String, Parameter> componentParameters) {
    List<Operation> out = new ArrayList<>();
    for (var pathEntry : rawPaths.entrySet()) {
      PathTemplate template = PathTemplate.compile(pathEntry.getKey());
      Map<String, Object> pathItem = (Map<String, Object>) pathEntry.getValue();
      for (HttpMethod m : HttpMethod.values()) {
        Object opRaw = pathItem.get(m.name().toLowerCase(Locale.ROOT));
        if (opRaw instanceof Map<?, ?> opMap) {
          out.add(parseOperation(m, template, (Map<String, Object>) opMap, componentParameters));
        }
      }
    }
    return List.copyOf(out);
  }

  @SuppressWarnings("unchecked")
  private static Operation parseOperation(
      HttpMethod method,
      PathTemplate path,
      Map<String, Object> raw,
      Map<String, Parameter> componentParameters) {
    String opId = (String) raw.get("operationId");
    Optional<RequestBody> body =
        Optional.ofNullable((Map<String, Object>) raw.get("requestBody"))
            .map(Spec::parseRequestBody);
    List<Parameter> params =
        Optional.ofNullable((List<Map<String, Object>>) raw.get("parameters"))
            .map(
                list ->
                    list.stream()
                        .map(p -> resolveParameterOrParse(p, componentParameters))
                        .toList())
            .orElse(List.of());
    Map<String, Response> responses =
        parseResponses((Map<String, Object>) raw.getOrDefault("responses", Map.of()));
    return new Operation(opId, method, path, body, params, responses);
  }

  @SuppressWarnings("unchecked")
  private static Parameter resolveParameterOrParse(
      Map<String, Object> raw, Map<String, Parameter> componentParameters) {
    String ref = (String) raw.get("$ref");
    if (ref != null) {
      String name = stripPrefix(ref, "#/components/parameters/");
      Parameter p = componentParameters.get(name);
      if (p == null) throw new IllegalArgumentException("unknown parameter ref: " + ref);
      return p;
    }
    return parseParameter(raw);
  }

  @SuppressWarnings("unchecked")
  private static RequestBody parseRequestBody(Map<String, Object> raw) {
    Map<String, Object> contentRaw = (Map<String, Object>) raw.getOrDefault("content", Map.of());
    Map<String, MediaType> content = new LinkedHashMap<>();
    for (var e : contentRaw.entrySet()) {
      Map<String, Object> mt = (Map<String, Object>) e.getValue();
      content.put(
          e.getKey(),
          new MediaType(
              SchemaParser.parse(
                  (Map<String, Object>) mt.getOrDefault("schema", Map.of("type", "object")))));
    }
    return new RequestBody(Boolean.TRUE.equals(raw.get("required")), Map.copyOf(content));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Response> parseResponses(Map<String, Object> raw) {
    Map<String, Response> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      Map<String, Object> r = (Map<String, Object>) e.getValue();
      Map<String, Object> contentRaw = (Map<String, Object>) r.getOrDefault("content", Map.of());
      Map<String, MediaType> content = new LinkedHashMap<>();
      for (var ce : contentRaw.entrySet()) {
        Map<String, Object> mt = (Map<String, Object>) ce.getValue();
        if (mt.containsKey("schema")) {
          content.put(
              ce.getKey(),
              new MediaType(SchemaParser.parse((Map<String, Object>) mt.get("schema"))));
        }
      }
      out.put(e.getKey(), new Response(Map.copyOf(content)));
    }
    return Map.copyOf(out);
  }
}
