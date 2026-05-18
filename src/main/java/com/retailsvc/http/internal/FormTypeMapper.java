package com.retailsvc.http.internal;

import com.retailsvc.http.TypeMapper;

/**
 * Built-in {@link TypeMapper} for {@code application/x-www-form-urlencoded}. Reads delegate to
 * {@link FormUrlEncodedParser}. Writes are not supported — form-encoded responses are unusual and
 * intentionally left out until a real need surfaces.
 */
public final class FormTypeMapper implements TypeMapper {

  private final FormUrlEncodedParser parser = new FormUrlEncodedParser();

  @Override
  public Object readFrom(byte[] body, String contentTypeHeader) {
    return parser.parse(body, contentTypeHeader);
  }

  @Override
  public byte[] writeTo(Object value) {
    throw new UnsupportedOperationException(
        "application/x-www-form-urlencoded write is not supported; register a custom TypeMapper");
  }
}
