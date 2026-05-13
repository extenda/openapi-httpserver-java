package com.retailsvc.http;

import java.io.IOException;

/**
 * Handles a single request identified by OpenAPI {@code operationId}. Registered on {@link
 * OpenApiServer.Builder#handlers(java.util.Map)} by operation ID.
 */
@FunctionalInterface
public interface RequestHandler {
  void handle(Request request) throws IOException;
}
