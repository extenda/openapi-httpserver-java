package com.retailsvc.http.openapi.model;

@FunctionalInterface
public interface RequestBodyMapper {

  <T> T mapFrom(byte[] body);
}
