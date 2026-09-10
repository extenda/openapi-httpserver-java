package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;

import com.retailsvc.http.BadRequestException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads the raw request body, transparently inflating a gzip {@code Content-Encoding} under a hard
 * cap on the decompressed size. Immutable and shared across requests.
 */
public final class RequestBodyReader {

  /** Default ceiling on the inflated size of a gzip request body: 10 MiB. */
  public static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 10L * 1024 * 1024;

  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String CONTENT_LENGTH = "Content-Length";
  private static final int BUFFER_SIZE = 8192;

  private final long maxDecompressedBytes;

  public RequestBodyReader(long maxDecompressedBytes) {
    if (maxDecompressedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxDecompressedBytes must be positive, got " + maxDecompressedBytes);
    }
    this.maxDecompressedBytes = maxDecompressedBytes;
  }

  /**
   * Reads and decodes the request body.
   *
   * @throws BadRequestException 415 when the coding is not one this server decodes, 413 when the
   *     inflated body exceeds the cap, 400 when the gzip stream is malformed or truncated
   */
  public Body read(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getRequestHeaders();
    String header = headers.getFirst(CONTENT_ENCODING);
    byte[] raw = exchange.getRequestBody().readAllBytes();
    return switch (ContentEncodingHeader.parse(header)) {
      case NONE -> new Body(raw, headers::getFirst);
      case GZIP -> decoded(inflate(raw), headers);
      case UNSUPPORTED ->
          throw new BadRequestException(
              HTTP_UNSUPPORTED_TYPE, "unsupported Content-Encoding: " + header);
    };
  }

  /**
   * Inflates a complete gzip member. The body is buffered before inflating so that an empty body
   * stays an empty body — inflating the exchange stream directly would fail at construction and be
   * indistinguishable from a truncated stream.
   */
  private byte[] inflate(byte[] raw) throws IOException {
    if (raw.length == 0) {
      return raw;
    }
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw), BUFFER_SIZE)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[BUFFER_SIZE];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > maxDecompressedBytes) {
          throw new BadRequestException(
              HTTP_ENTITY_TOO_LARGE,
              "decompressed request body exceeds " + maxDecompressedBytes + " bytes");
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (ZipException | EOFException e) {
      throw new BadRequestException(HTTP_BAD_REQUEST, "malformed gzip request body", e);
    }
  }

  /**
   * Presents the inflated body as if it had arrived uncoded: the coding is gone, so reporting it
   * alongside the decoded bytes would misdescribe them, and the stored length measures the
   * compressed payload rather than what the handler can read.
   */
  private static Body decoded(byte[] bytes, Headers headers) {
    String inflatedLength = Integer.toString(bytes.length);
    return new Body(
        bytes,
        name -> {
          if (CONTENT_ENCODING.equalsIgnoreCase(name)) {
            return null;
          }
          if (CONTENT_LENGTH.equalsIgnoreCase(name)) {
            return inflatedLength;
          }
          return headers.getFirst(name);
        });
  }

  /** A decoded request body and the header view a handler should see alongside it. */
  @SuppressWarnings("java:S6218")
  public record Body(byte[] bytes, UnaryOperator<String> headerLookup) {}
}
