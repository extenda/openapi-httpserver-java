package com.retailsvc.http;

@FunctionalInterface
public interface JsonMapper {
  Object mapFrom(byte[] body);
}
