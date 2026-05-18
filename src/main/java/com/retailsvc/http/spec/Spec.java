package com.retailsvc.http.spec;

import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.SchemaParser;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.retailsvc.http.spec.security.SecuritySchemeParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Map<String, Parameter> componentParameters,
    String basePath,
    Map<String, Schema> schemaRefIndex,
    Map<String, Parameter> parameterRefIndex,
    Map<String, Object> extensions,
    Map<String, SecurityScheme> securitySchemes,
    List<SecurityRequirement> security) {

  private static final String SCHEMA_KEY = "schema";
  private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
  private static final String PARAMETER_REF_PREFIX = "#/components/parameters/";

  static Map<String, Object> extractExtensions(Map<String, Object> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : raw.entrySet()) {
      if (e.getKey().startsWith("x-")) {
        out.put(e.getKey(), e.getValue());
      }
    }
    return Map.copyOf(out);
  }

  private static final String GSON_CLASS = "com.google.gson.Gson";
  private static final String SNAKEYAML_CLASS = "org.yaml.snakeyaml.Yaml";

  /**
   * Reads an OpenAPI specification from {@code path}. Picks the parser by file extension:
   *
   * <ul>
   *   <li>{@code .json} → Gson must be on the classpath.
   *   <li>{@code .yaml} or {@code .yml} → SnakeYAML must be on the classpath.
   * </ul>
   *
   * <p>Both Gson and SnakeYAML are optional dependencies of this library. If the parser for the
   * file's extension is not present, throws {@link IllegalStateException} — register your own
   * parser and call {@link #from(Map)} instead.
   *
   * @throws UncheckedIOException if the file cannot be read
   * @throws IllegalStateException if the required parser is not on the classpath, or if the file
   *     has an unrecognised extension
   */
  public static Spec fromPath(Path path) {
    String text;
    try {
      text = Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read OpenAPI spec from " + path, e);
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    Map<String, Object> raw;
    if (name.endsWith(".json")) {
      raw = parseJson(text);
    } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
      raw = parseYaml(text);
    } else {
      throw new IllegalStateException(
          "Unrecognised OpenAPI spec extension for "
              + path
              + " — expected .json, .yaml, or .yml. Parse the file yourself and call"
              + " Spec.from(Map<String, Object>) instead.");
    }
    return from(raw);
  }

  private static Map<String, Object> parseJson(String text) {
    Class<?> gsonClass = loadOptional(GSON_CLASS, "JSON", "Gson");
    try {
      Object gson = gsonClass.getDeclaredConstructor().newInstance();
      Method fromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) fromJson.invoke(gson, text, Map.class);
      return raw;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to parse OpenAPI spec via Gson", e);
    }
  }

  private static Map<String, Object> parseYaml(String text) {
    Class<?> yamlClass = loadOptional(SNAKEYAML_CLASS, "YAML", "SnakeYAML");
    try {
      Object yaml = yamlClass.getDeclaredConstructor().newInstance();
      Method load = yamlClass.getMethod("load", String.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> raw = (Map<String, Object>) load.invoke(yaml, text);
      return raw;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to parse OpenAPI spec via SnakeYAML", e);
    }
  }

  private static Class<?> loadOptional(String className, String format, String libName) {
    try {
      return Class.forName(className, false, Spec.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "Spec.fromPath requires "
              + libName
              + " on the classpath for "
              + format
              + " specs. Add a "
              + libName
              + " dependency, or parse the file yourself and call"
              + " Spec.from(Map<String, Object>) instead.",
          e);
    }
  }

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
    Map<String, Object> rawSchemes =
        (Map<String, Object>) rawComponents.getOrDefault("securitySchemes", Map.of());
    Map<String, SecurityScheme> securitySchemes = new LinkedHashMap<>();
    for (var entry : rawSchemes.entrySet()) {
      securitySchemes.put(
          entry.getKey(), SecuritySchemeParser.parse((Map<String, Object>) entry.getValue()));
    }
    List<SecurityRequirement> rootSecurity =
        SecuritySchemeParser.parseRequirements((List<Object>) raw.get("security"));
    return new Spec(
        openapi,
        info,
        servers,
        operations,
        componentSchemas,
        componentParameters,
        computeBasePath(servers),
        indexByRef(componentSchemas, SCHEMA_REF_PREFIX),
        indexByRef(componentParameters, PARAMETER_REF_PREFIX),
        extractExtensions(raw),
        Map.copyOf(securitySchemes),
        rootSecurity);
  }

  private static String computeBasePath(List<Server> servers) {
    if (servers.isEmpty()) {
      throw new IllegalStateException("no servers declared");
    }
    return Optional.ofNullable(URI.create(servers.getFirst().url()).getPath()).orElse("");
  }

  private static <T> Map<String, T> indexByRef(Map<String, T> components, String prefix) {
    Map<String, T> out = LinkedHashMap.newLinkedHashMap(components.size());
    for (var e : components.entrySet()) {
      out.put(prefix + e.getKey(), e.getValue());
    }
    return Map.copyOf(out);
  }

  public Schema resolveSchema(String ref) {
    Schema s = schemaRefIndex.get(ref);
    if (s == null) {
      throw new IllegalArgumentException("unknown schema ref: " + ref);
    }
    return s;
  }

  public Parameter resolveParameter(String ref) {
    Parameter p = parameterRefIndex.get(ref);
    if (p == null) {
      throw new IllegalArgumentException("unknown parameter ref: " + ref);
    }
    return p;
  }

  private static String stripPrefix(String ref, String prefix) {
    if (!ref.startsWith(prefix)) {
      throw new IllegalArgumentException("ref does not start with " + prefix + ": " + ref);
    }
    return ref.substring(prefix.length());
  }

  private static Info parseInfo(Map<String, Object> raw) {
    return new Info((String) raw.get("title"), (String) raw.get("version"), extractExtensions(raw));
  }

  private static List<Server> parseServers(List<Map<String, Object>> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    return raw.stream().map(m -> new Server((String) m.get("url"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Schema> parseComponentSchemas(Map<String, Object> rawComponents) {
    Map<String, Object> rawSchemas =
        (Map<String, Object>) rawComponents.getOrDefault("schemas", Map.of());
    Map<String, Schema> out = new LinkedHashMap<>();
    for (var e : rawSchemas.entrySet()) {
      out.put(e.getKey(), SchemaParser.parse(e.getValue()));
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
        SchemaParser.parse(raw.getOrDefault(SCHEMA_KEY, Map.of("type", "string"))));
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
    Optional<List<SecurityRequirement>> opSecurity =
        raw.containsKey("security")
            ? Optional.of(
                SecuritySchemeParser.parseRequirements((List<Object>) raw.get("security")))
            : Optional.empty();
    return new Operation(
        opId, method, path, body, params, responses, extractExtensions(raw), opSecurity);
  }

  private static Parameter resolveParameterOrParse(
      Map<String, Object> raw, Map<String, Parameter> componentParameters) {
    String ref = (String) raw.get("$ref");
    if (ref != null) {
      String name = stripPrefix(ref, PARAMETER_REF_PREFIX);
      Parameter p = componentParameters.get(name);
      if (p == null) {
        throw new IllegalArgumentException("unknown parameter ref: " + ref);
      }
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
          e.getKey().toLowerCase(java.util.Locale.ROOT),
          new MediaType(SchemaParser.parse(mt.getOrDefault(SCHEMA_KEY, Map.of("type", "object")))));
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
        if (mt.containsKey(SCHEMA_KEY)) {
          content.put(
              ce.getKey().toLowerCase(java.util.Locale.ROOT),
              new MediaType(SchemaParser.parse(mt.get(SCHEMA_KEY))));
        }
      }
      out.put(e.getKey(), new Response(Map.copyOf(content)));
    }
    return Map.copyOf(out);
  }
}
