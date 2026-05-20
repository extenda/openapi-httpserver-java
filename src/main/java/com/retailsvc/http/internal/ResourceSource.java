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

  /**
   * Returns the resource's byte length, resolved at construction.
   *
   * @return byte length of the resource
   */
  long length();

  /**
   * Returns the media type inferred from the resource's file extension.
   *
   * @return media type string
   */
  String contentType();

  /**
   * Returns a human-readable description used in error messages and logs.
   *
   * @return description of the resource
   */
  String describe();

  /**
   * Opens a fresh {@link InputStream} for reading the resource bytes.
   *
   * @return a new input stream the caller must close
   * @throws IOException if the resource cannot be opened
   */
  InputStream open() throws IOException;

  /**
   * Creates a {@code ResourceSource} backed by a classpath resource.
   *
   * @param classpathResource absolute classpath path (must start with {@code /})
   * @return a fail-fast handle to the resource
   * @throws IllegalArgumentException if the resource is missing or unreadable
   */
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

  /**
   * Creates a {@code ResourceSource} backed by a filesystem file.
   *
   * @param file path to a regular file
   * @return a fail-fast handle to the file
   * @throws IllegalArgumentException if {@code file} is not a regular file or its size cannot be
   *     read
   */
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

  /**
   * Maps a file extension to a Content-Type string.
   *
   * @param path file name or path; only the extension is inspected (case-insensitive)
   * @return the inferred media type, or {@code application/octet-stream} if the extension is
   *     unknown
   */
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

  /**
   * Classpath-backed {@link ResourceSource}.
   *
   * @param path absolute classpath path (must start with {@code /})
   * @param length pre-resolved byte length
   * @param contentType media type inferred from the path's extension
   */
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

  /**
   * Filesystem-backed {@link ResourceSource}.
   *
   * @param path file path
   * @param length pre-resolved byte length
   * @param contentType media type inferred from the file's extension
   */
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
