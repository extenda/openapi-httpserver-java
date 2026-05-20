package com.retailsvc.http.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A streamable, fail-fast handle to a resource on the classpath or filesystem. Existence and length
 * are resolved at construction; the underlying bytes are not buffered. Each {@link #open()} call
 * returns a fresh {@link InputStream} that the caller must close.
 */
public sealed interface ResourceSource {

  long length();

  String contentType();

  String describe();

  InputStream open() throws IOException;

  static ResourceSource ofClasspath(String classpathResource) {
    Objects.requireNonNull(classpathResource, "classpathResource");
    long length;
    try (InputStream in = ResourceSource.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
      }
      length = in.transferTo(OutputStream.nullOutputStream());
    } catch (IOException io) {
      throw new IllegalArgumentException(
          "failed reading classpath resource: " + classpathResource, io);
    }
    return new Classpath(classpathResource, length, contentTypeFor(classpathResource));
  }

  static ResourceSource ofFile(Path file) {
    Objects.requireNonNull(file, "file");
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("file not found or not a regular file: " + file);
    }
    long length;
    try {
      length = Files.size(file);
    } catch (IOException io) {
      throw new IllegalArgumentException("failed reading file: " + file, io);
    }
    return new File(file, length, contentTypeFor(file.getFileName().toString()));
  }

  static String contentTypeFor(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return "application/json";
    }
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
      return "application/yaml";
    }
    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
      return "text/html; charset=utf-8";
    }
    if (lower.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (lower.endsWith(".js")) {
      return "text/javascript; charset=utf-8";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    return "application/octet-stream";
  }

  record Classpath(String path, long length, String contentType) implements ResourceSource {
    @Override
    public InputStream open() throws IOException {
      InputStream in = ResourceSource.class.getResourceAsStream(path);
      if (in == null) {
        throw new IOException("classpath resource disappeared: " + path);
      }
      return in;
    }

    @Override
    public String describe() {
      return "classpath:" + path;
    }
  }

  record File(Path path, long length, String contentType) implements ResourceSource {
    @Override
    public InputStream open() throws IOException {
      return Files.newInputStream(path);
    }

    @Override
    public String describe() {
      return path.toString();
    }
  }
}
