package com.retailsvc.http.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Internal carrier for streaming response bodies. Constructed by {@code Response} streaming
 * factories and recognised by the renderer; never exposed as a public type.
 */
public sealed interface BodyWriter permits BodyWriter.Sized, BodyWriter.Chunked {

  /**
   * Writes the body bytes to the exchange output stream.
   *
   * <p>Declares {@link IOException} so the renderer can let it propagate up to the server's
   * exception filter rather than swallowing it at the body-writing layer.
   *
   * @param out the exchange output stream to write the response body to
   * @throws IOException if writing to the output stream fails
   */
  void writeTo(OutputStream out) throws IOException;

  /**
   * Known {@code Content-Length}.
   *
   * @param length the exact {@code Content-Length} to set on the response
   * @param writer writes the body bytes to the exchange output stream
   */
  record Sized(long length, IOConsumer writer) implements BodyWriter {
    @Override
    public void writeTo(OutputStream out) throws IOException {
      writer.accept(out);
    }
  }

  /**
   * Unknown length — chunked transfer encoding.
   *
   * <p>The renderer uses chunked transfer encoding because the body length is unknown ahead of
   * time.
   *
   * @param writer writes the body bytes to the exchange output stream
   */
  record Chunked(IOConsumer writer) implements BodyWriter {
    @Override
    public void writeTo(OutputStream out) throws IOException {
      writer.accept(out);
    }
  }

  /** {@code Consumer<OutputStream>} that is allowed to throw {@link IOException}. */
  @FunctionalInterface
  interface IOConsumer {
    /**
     * Single write callback that streams bytes to the given output stream.
     *
     * @param out the exchange output stream to write to
     * @throws IOException if writing to the output stream fails
     */
    void accept(OutputStream out) throws IOException;
  }
}
