package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;

import com.retailsvc.http.BadRequestException;
import com.retailsvc.http.ContentCoding;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;

/**
 * Decodes a coded request body as it is read, under a cap on the decoded size. The streaming
 * counterpart of {@link RequestBodyReader#read}: the same failures surface, as {@link
 * BadRequestException}s from {@code read} rather than before the handler runs.
 *
 * <p>The decoder is opened on the first read, and only once a byte has arrived, so an empty body
 * reads as empty rather than failing the way a coding with no header would — as it does when
 * buffered.
 *
 * <p>Only the coding's own failures become a 400. An {@link IOException} from the connection itself
 * — the client going away mid-upload — propagates unchanged, as it would for an uncoded body; it is
 * told apart by tagging what the raw stream throws before the decoder sees it.
 */
final class DecodingInputStream extends InputStream {

  private final ContentCoding coding;
  private final PushbackInputStream raw;
  private final long maxDecodedBytes;
  private InputStream decoded;
  private boolean empty;
  private long count;

  DecodingInputStream(ContentCoding coding, InputStream raw, long maxDecodedBytes) {
    this.coding = coding;
    this.raw = new PushbackInputStream(new Source(raw), 1);
    this.maxDecodedBytes = maxDecodedBytes;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n == -1 ? -1 : one[0] & 0xFF;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }
    int n;
    try {
      if (!open()) {
        return -1;
      }
      n = decoded.read(b, off, len);
    } catch (IOException e) {
      IOException transport = transportFailure(e);
      if (transport != null) {
        throw transport;
      }
      throw new BadRequestException(
          HTTP_BAD_REQUEST, "malformed " + coding.token() + " request body", e);
    }
    if (n > 0) {
      count += n;
      if (count > maxDecodedBytes) {
        throw new BadRequestException(
            HTTP_ENTITY_TOO_LARGE,
            "decompressed request body exceeds " + maxDecodedBytes + " bytes");
      }
    }
    return n;
  }

  /** Opens the decoder once the body has a byte; {@code false} when the body is empty. */
  private boolean open() throws IOException {
    if (decoded != null) {
      return true;
    }
    if (empty) {
      return false;
    }
    int first = raw.read();
    if (first == -1) {
      empty = true;
      return false;
    }
    raw.unread(first);
    decoded = coding.decode(raw);
    return true;
  }

  /** The connection's own exception, if {@code e} is or wraps one; {@code null} otherwise. */
  private static IOException transportFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof TransportException tagged) {
        return tagged.original();
      }
    }
    return null;
  }

  /** The raw body, with every failure tagged as the connection's rather than the coding's. */
  private static final class Source extends FilterInputStream {

    Source(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      try {
        return super.read();
      } catch (IOException e) {
        throw new TransportException(e);
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        return super.read(b, off, len);
      } catch (IOException e) {
        throw new TransportException(e);
      }
    }

    @Override
    public long skip(long n) throws IOException {
      try {
        return super.skip(n);
      } catch (IOException e) {
        throw new TransportException(e);
      }
    }

    @Override
    public int available() throws IOException {
      try {
        return super.available();
      } catch (IOException e) {
        throw new TransportException(e);
      }
    }
  }

  /** Carries a connection failure through the decoder so it can be unwrapped on the other side. */
  private static final class TransportException extends IOException {

    TransportException(IOException original) {
      super(original);
    }

    IOException original() {
      return (IOException) getCause();
    }
  }

  @Override
  public void close() throws IOException {
    if (decoded != null) {
      decoded.close();
    } else {
      raw.close();
    }
  }
}
