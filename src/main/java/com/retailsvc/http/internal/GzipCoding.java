package com.retailsvc.http.internal;

import com.retailsvc.http.ContentCoding;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** The built-in {@code gzip} coding, which every server offers. */
final class GzipCoding implements ContentCoding {

  private static final Set<String> ALIASES = Set.of("x-gzip");
  private static final int BUFFER_SIZE = 8192;

  @Override
  public String token() {
    return "gzip";
  }

  @Override
  public Set<String> aliases() {
    return ALIASES;
  }

  @Override
  public InputStream decode(InputStream coded) throws IOException {
    return new GZIPInputStream(coded, BUFFER_SIZE);
  }

  /**
   * Sync-flushes, so a flushed streamed response reaches the client then rather than when the
   * deflater's buffer fills. Whole bodies are coded without a flush, so they pay nothing for it.
   */
  @Override
  public OutputStream encode(OutputStream sink) throws IOException {
    return new GZIPOutputStream(sink, true);
  }
}
