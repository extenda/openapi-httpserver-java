package com.retailsvc.http.internal;

import com.retailsvc.http.TypeMapper;
import java.nio.charset.StandardCharsets;

/**
 * Built-in {@link TypeMapper} for {@code text/plain}. Reads decode bytes using the charset declared
 * on {@code Content-Type} (default UTF-8). Writes return {@code String.valueOf(value)} encoded as
 * UTF-8.
 */
public final class TextTypeMapper implements TypeMapper {

  private final TextPlainParser parser = new TextPlainParser();

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return parser.parse(body, contentTypeHeader);
  }

  @Override
  public byte[] writeTo(Object value) {
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }
}
