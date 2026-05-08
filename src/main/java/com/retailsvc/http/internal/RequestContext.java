package com.retailsvc.http.internal;

import java.util.Map;

/**
 * Immutable per-request data populated by {@link RequestPreparationFilter} and read by handlers via
 * {@link com.retailsvc.http.Request}. Bound to a {@link ScopedValue} for the duration of a single
 * request — never written to the {@code HttpExchange}'s context-shared attribute map.
 */
public record RequestContext(
    byte[] body, Object parsedBody, String operationId, Map<String, String> pathParameters) {}
