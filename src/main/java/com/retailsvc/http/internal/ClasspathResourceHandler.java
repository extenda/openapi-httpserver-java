package com.retailsvc.http.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Eagerly-loaded bytes for a classpath resource. Content-Type is inferred from the file extension.
 * Throws {@link IllegalArgumentException} if the resource is missing.
 */
public final class ClasspathResourceHandler {

  private final byte[] bytes;
  private final String contentType;

  public ClasspathResourceHandler(String classpathResource) {
    try (InputStream in = ClasspathResourceHandler.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalArgumentException("classpath resource not found: " + classpathResource);
      }
      this.bytes = in.readAllBytes();
    } catch (IOException io) {
      throw new IllegalArgumentException(
          "failed reading classpath resource: " + classpathResource, io);
    }
    this.contentType = contentTypeFor(classpathResource);
  }

  public byte[] bytes() {
    return bytes;
  }

  public String contentType() {
    return contentType;
  }

  private static String contentTypeFor(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return "application/json";
    }
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
      return "application/yaml";
    }
    if (lower.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    return "application/octet-stream";
  }
}
