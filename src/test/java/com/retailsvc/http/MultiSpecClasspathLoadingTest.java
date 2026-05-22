package com.retailsvc.http;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.support.SpecAnchor;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiSpecClasspathLoadingTest {

  @Test
  void loadsTwoSpecsFromDirectoryClasspath() throws Exception {
    Spec v1 = Spec.fromClasspath(SpecAnchor.class, "/schemas/v1/openapi.json");
    Spec v2 = Spec.fromClasspath(SpecAnchor.class, "/schemas/v2/openapi.json");

    assertThat(v1.basePath()).isEqualTo("/api/v1");
    assertThat(v2.basePath()).isEqualTo("/api/v2");

    bootAndAssertBothPingsReturnOk(v1, v2);
  }

  @Test
  void loadsTwoSpecsFromJarClasspath(@TempDir Path tmp) throws Exception {
    Path jarFile = buildSchemasJar(tmp);

    try (URLClassLoader cl =
        new URLClassLoader(new URL[] {jarFile.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> anchor = Class.forName("com.retailsvc.http.support.SpecAnchor", true, cl);

      Spec v1 = Spec.fromClasspath(anchor, "/schemas/v1/openapi.json");
      Spec v2 = Spec.fromClasspath(anchor, "/schemas/v2/openapi.json");

      assertThat(v1.basePath()).isEqualTo("/api/v1");
      assertThat(v2.basePath()).isEqualTo("/api/v2");

      bootAndAssertBothPingsReturnOk(v1, v2);
    }
  }

  private static void bootAndAssertBothPingsReturnOk(Spec v1, Spec v2) throws Exception {
    Map<String, RequestHandler> v1Handlers = handlersFor(v1, req -> Response.ok(Map.of("v", 1)));
    Map<String, RequestHandler> v2Handlers = handlersFor(v2, req -> Response.ok(Map.of("v", 2)));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .port(0)
            .addSpec(v1, v1Handlers)
            .addSpec(v2, v2Handlers)
            .useExternalAuthentication()
            .build()) {

      int port = server.listenPort();
      HttpResponse<String> r1 = get("http://localhost:" + port + "/api/v1/ping");
      HttpResponse<String> r2 = get("http://localhost:" + port + "/api/v2/ping");
      assertThat(r1.statusCode()).isEqualTo(HTTP_OK);
      assertThat(r1.body()).contains("\"v\":1");
      assertThat(r2.statusCode()).isEqualTo(HTTP_OK);
      assertThat(r2.body()).contains("\"v\":2");
    }
  }

  private static Path buildSchemasJar(Path tmp) throws IOException {
    Path jar = tmp.resolve("specs.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      copyToJar(out, "schemas/v1/openapi.json");
      copyToJar(out, "schemas/v2/openapi.json");
      copyToJar(out, "com/retailsvc/http/support/SpecAnchor.class");
    }
    return jar;
  }

  private static void copyToJar(JarOutputStream out, String resourcePath) throws IOException {
    try (InputStream in =
        MultiSpecClasspathLoadingTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("missing test resource: " + resourcePath);
      }
      out.putNextEntry(new JarEntry(resourcePath));
      in.transferTo(out);
      out.closeEntry();
    }
  }

  private static Map<String, RequestHandler> handlersFor(Spec spec, RequestHandler shared) {
    Map<String, RequestHandler> out = new LinkedHashMap<>();
    spec.operations().forEach(op -> out.put(op.operationId(), shared));
    return out;
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
