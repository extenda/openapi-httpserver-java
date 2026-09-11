package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.ContentCoding;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Coded;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Identity;
import com.retailsvc.http.internal.ContentEncodingHeader.RequestCoding.Unsupported;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reads the raw request body, transparently decoding a registered {@code Content-Encoding} under a
 * hard cap on the decoded size. Immutable and shared across requests.
 */
public final class RequestBodyReader {

  /** Default ceiling on the decoded size of a coded request body: 10 MiB. */
  public static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 10L * 1024 * 1024;

  private static final String CONTENT_ENCODING = "Content-Encoding";
  private static final String CONTENT_LENGTH = "Content-Length";

  private final long maxDecompressedBytes;
  private final int readLimit;
  private final Map<String, ContentCoding> decoders;

  public RequestBodyReader(long maxDecompressedBytes, Map<String, ContentCoding> decoders) {
    if (maxDecompressedBytes <= 0) {
      throw new IllegalArgumentException(
          "maxDecompressedBytes must be positive, got " + maxDecompressedBytes);
    }
    this.maxDecompressedBytes = maxDecompressedBytes;
    this.readLimit = (int) Math.min(maxDecompressedBytes, Integer.MAX_VALUE - 1L) + 1;
    this.decoders = Map.copyOf(decoders);
  }

  /**
   * Reads and decodes the request body.
   *
   * @throws BadRequestException 415 when the coding is not one this server decodes, 413 when the
   *     decoded body exceeds the cap, 400 when the coded body is malformed or truncated
   */
  public Body read(HttpExchange exchange) throws IOException {
    Headers headers = exchange.getRequestHeaders();
    String header = headers.getFirst(CONTENT_ENCODING);
    byte[] raw = exchange.getRequestBody().readAllBytes();
    return switch (ContentEncodingHeader.parse(header, decoders)) {
      case Identity _ -> new Body(raw, headers::getFirst);
      case Coded(ContentCoding coding) -> decoded(decode(coding, raw), headers);
      case Unsupported _ ->
          throw new BadRequestException(
              HTTP_UNSUPPORTED_TYPE, "unsupported Content-Encoding: " + header);
    };
  }

  /**
   * Decodes a complete coded body. The body is buffered before this runs, so the coding only ever
   * reads memory: any read failure is the body's fault, and an empty body stays empty rather than
   * failing the way a stream with no header would.
   */
  private byte[] decode(ContentCoding coding, byte[] raw) {
    if (raw.length == 0) {
      return raw;
    }
    try (InputStream in = coding.decode(new ByteArrayInputStream(raw))) {
      byte[] decoded = in.readNBytes(readLimit);
      if (decoded.length > maxDecompressedBytes) {
        throw new BadRequestException(
            HTTP_ENTITY_TOO_LARGE,
            "decompressed request body exceeds " + maxDecompressedBytes + " bytes");
      }
      return decoded;
    } catch (IOException e) {
      throw new BadRequestException(
          HTTP_BAD_REQUEST, "malformed " + coding.token() + " request body", e);
    }
  }

  /**
   * Presents the decoded body as if it had arrived uncoded: the coding is gone, so reporting it
   * alongside the decoded bytes would misdescribe them, and the stored length measures the coded
   * payload rather than what the handler can read.
   */
  private static Body decoded(byte[] bytes, Headers headers) {
    String decodedLength = Integer.toString(bytes.length);
    return new Body(
        bytes,
        name -> {
          if (CONTENT_ENCODING.equalsIgnoreCase(name)) {
            return null;
          }
          if (CONTENT_LENGTH.equalsIgnoreCase(name)) {
            return decodedLength;
          }
          return headers.getFirst(name);
        });
  }

  /** A decoded request body and the header view a handler should see alongside it. */
  @SuppressWarnings("java:S6218")
  public record Body(byte[] bytes, UnaryOperator<String> headerLookup) {}
}
