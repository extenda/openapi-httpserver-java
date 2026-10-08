package com.retailsvc.http;

import java.util.Objects;

/**
 * A {@link RequestHandler} that reads the request body as it arrives instead of receiving it
 * buffered. Register it like any other handler, by {@code operationId} or as an extra route; the
 * type alone opts the route in:
 *
 * <pre>{@code
 * Map.of("uploadFile", (StreamingRequestHandler) request -> {
 *   try (InputStream in = request.bodyStream()) {
 *     storage.upload(in);
 *   } catch (IOException e) {
 *     throw new UncheckedIOException(e);
 *   }
 *   return Response.accepted();
 * });
 * }</pre>
 *
 * <p>The library still validates path, query and header parameters, the {@code Content-Type}
 * against the operation's {@code requestBody.content}, and that a {@code required} body is not
 * empty. The body itself is never parsed, so it is not validated against its schema, and {@link
 * Request#bytes()}, {@link Request#parsed()} and {@link Request#asPojo(Class)} throw. Read the body
 * through {@link Request#bodyStream()}, once, before {@link #handle(Request)} returns.
 *
 * <p>A registered {@code Content-Encoding} is decoded as the handler reads, and the decoded size is
 * held to {@link OpenApiServer.Builder#maxDecompressedStreamingRequestBytes(long)}, which defaults
 * to {@link OpenApiServer.Builder#maxDecompressedRequestBytes(long)}. A body that exceeds it, or
 * fails to decode, surfaces from the stream's {@code read} as a {@link BadRequestException} (413 or
 * 400) — possibly after the handler has already passed earlier bytes on. An uncoded body has no
 * size limit. To receive a coded body exactly as sent instead, see {@link #raw}.
 *
 * <p>Concurrent streaming requests can be capped server-wide with {@link
 * OpenApiServer.Builder#maxConcurrentStreamingRequests(int)}.
 */
@FunctionalInterface
public interface StreamingRequestHandler extends RequestHandler {

  /**
   * Whether the server decodes a registered {@code Content-Encoding} before the handler reads the
   * body. {@code true} by default. When {@code false}, the handler reads the body exactly as sent:
   * still coded, with no size cap, and with {@code Content-Encoding} and {@code Content-Length}
   * visible; a coding the server doesn't know is passed on rather than rejected with 415.
   */
  default boolean decodeContent() {
    return true;
  }

  /**
   * Wraps {@code handler} so that it reads the body exactly as sent, without decoding; see {@link
   * #decodeContent()}. For handlers written as lambdas, which can't override it.
   */
  static StreamingRequestHandler raw(StreamingRequestHandler handler) {
    Objects.requireNonNull(handler, "handler must not be null");
    return new StreamingRequestHandler() {
      @Override
      public Response handle(Request request) {
        return handler.handle(request);
      }

      @Override
      public boolean decodeContent() {
        return false;
      }
    };
  }
}
