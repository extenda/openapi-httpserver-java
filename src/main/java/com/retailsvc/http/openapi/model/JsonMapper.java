package com.retailsvc.http.openapi.model;

/** Interface to support multiple de-/serializers such as Gson, Jackson etc. */
@FunctionalInterface
public interface JsonMapper {

  <T> T mapFrom(byte[] body);
}
