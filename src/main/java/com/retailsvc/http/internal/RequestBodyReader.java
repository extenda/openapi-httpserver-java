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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Reads the raw request body, transparently decoding a registered {@code Content-Encoding} under a
 * hard cap on the decoded size, either buffered in full or as a stream for a streaming handler.
 * Immutable and shared across requests.
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
      case Identity _ -> new Body(raw, copy(headers));
      case Coded(ContentCoding coding) -> decoded(decode(coding, raw), headers);
      case Unsupported _ ->
          throw new BadRequestException(
              HTTP_UNSUPPORTED_TYPE, "unsupported Content-Encoding: " + header);
    };
  }

  /**
   * Opens the request body as a stream, decoded as it is read. Nothing is read here, so a coded
   * body's failures — malformed (400) or over the cap (413) — surface from the stream's {@code
   * read} as a {@link BadRequestException}.
   *
   * @throws BadRequestException 415 when the coding is not one this server decodes
   */
  public Streamed stream(HttpExchange exchange) {
    return stream(exchange, true);
  }

  /**
   * Opens the request body as a stream. With {@code decode} set, as {@link #stream(HttpExchange)};
   * without it, the body is passed on exactly as sent — still coded, uncapped, whatever its {@code
   * Content-Encoding} — with the headers unchanged.
   *
   * @throws BadRequestException 415 when decoding and the coding is not one this server decodes
   */
  public Streamed stream(HttpExchange exchange, boolean decode) {
    Headers headers = exchange.getRequestHeaders();
    InputStream raw = exchange.getRequestBody();
    if (!decode) {
      return new Streamed(raw, copy(headers));
    }
    String header = headers.getFirst(CONTENT_ENCODING);
    return switch (ContentEncodingHeader.parse(header, decoders)) {
      case Identity _ -> new Streamed(raw, copy(headers));
      case Coded(ContentCoding coding) ->
          new Streamed(
              new DecodingInputStream(coding, raw, maxDecompressedBytes),
              decodedHeaders(headers, null));
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
    return new Body(bytes, decodedHeaders(headers, Integer.toString(bytes.length)));
  }

  /** The header view of a decoded body; a {@code null} length means it is not known up front. */
  private static Map<String, List<String>> decodedHeaders(Headers headers, String decodedLength) {
    TreeMap<String, List<String>> view = mutableCopy(headers);
    view.remove(CONTENT_ENCODING);
    view.remove(CONTENT_LENGTH);
    if (decodedLength != null) {
      view.put(CONTENT_LENGTH, List.of(decodedLength));
    }
    return Collections.unmodifiableMap(view);
  }

  private static Map<String, List<String>> copy(Headers headers) {
    return Collections.unmodifiableMap(mutableCopy(headers));
  }

  /** A case-insensitive copy, so the view keeps the lookup semantics of {@link Headers}. */
  private static TreeMap<String, List<String>> mutableCopy(Headers headers) {
    TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    return copy;
  }

  private static UnaryOperator<String> firstValue(Map<String, List<String>> headers) {
    return name -> {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.getFirst();
    };
  }

  /** A decoded request body and the headers a handler should see alongside it. */
  @SuppressWarnings("java:S6218")
  public record Body(byte[] bytes, Map<String, List<String>> headers) {

    /** First-value, case-insensitive lookup over {@link #headers()}. */
    public UnaryOperator<String> headerLookup() {
      return firstValue(headers);
    }
  }

  /** A request body still to be read, and the headers a handler should see alongside it. */
  public record Streamed(InputStream stream, Map<String, List<String>> headers) {

    /** First-value, case-insensitive lookup over {@link #headers()}. */
    public UnaryOperator<String> headerLookup() {
      return firstValue(headers);
    }
  }
}
