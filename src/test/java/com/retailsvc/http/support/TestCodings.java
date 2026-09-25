package com.retailsvc.http.support;

import com.retailsvc.http.ContentCoding;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Content codings for tests, built on java.util.zip so no test needs a compression library. */
public final class TestCodings {

  private TestCodings() {}

  /** The zlib {@code deflate} coding: a real, dependency-free coding to register from tests. */
  public static ContentCoding deflate() {
    return new ContentCoding() {
      @Override
      public String token() {
        return "deflate";
      }

      @Override
      public InputStream decode(InputStream coded) {
        return new InflaterInputStream(coded);
      }

      @Override
      public OutputStream encode(OutputStream sink) {
        return new DeflaterOutputStream(sink);
      }
    };
  }

  /** A coding that only carries its names; bodies pass through untouched. */
  public static ContentCoding named(String token, String... aliases) {
    Set<String> aliasSet = Set.of(aliases);
    return new ContentCoding() {
      @Override
      public String token() {
        return token;
      }

      @Override
      public Set<String> aliases() {
        return aliasSet;
      }

      @Override
      public InputStream decode(InputStream coded) {
        return coded;
      }

      @Override
      public OutputStream encode(OutputStream sink) {
        return sink;
      }
    };
  }
}
