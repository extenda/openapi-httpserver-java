package com.retailsvc.http.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Internal carrier for streaming response bodies. Constructed by {@code Response} streaming
 * factories and recognised by the renderer; never exposed as a public type.
 */
public sealed interface BodyWriter permits BodyWriter.Sized, BodyWriter.Chunked {

  void writeTo(OutputStream out) throws IOException;

  /** Known {@code Content-Length}. */
  record Sized(long length, IOConsumer writer) implements BodyWriter {
    @Override
    public void writeTo(OutputStream out) throws IOException {
      writer.accept(out);
    }
  }

  /** Unknown length — chunked transfer encoding. */
  record Chunked(IOConsumer writer) implements BodyWriter {
    @Override
    public void writeTo(OutputStream out) throws IOException {
      writer.accept(out);
    }
  }

  /** {@code Consumer<OutputStream>} that is allowed to throw {@link IOException}. */
  @FunctionalInterface
  interface IOConsumer {
    void accept(OutputStream out) throws IOException;
  }
}
