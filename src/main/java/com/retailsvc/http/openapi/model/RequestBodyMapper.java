package com.retailsvc.http.openapi.model;

public interface RequestBodyMapper {

  <T> T mapFrom(byte[] body);
}
