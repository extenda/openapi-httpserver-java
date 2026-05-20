package com.retailsvc.http.spec;

import com.retailsvc.http.spec.security.SecurityRequirement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved OpenAPI operation: a single (method, path) pair with its validation metadata, used by
 * the router and dispatcher to route requests to user-supplied handlers.
 *
 * @param operationId unique OpenAPI {@code operationId} identifying this operation
 * @param method HTTP method for this operation
 * @param path path template (possibly containing {@code {var}} placeholders)
 * @param requestBody request body definition, if any
 * @param parameters declared path, query, header and cookie parameters
 * @param responses response definitions keyed by status code (or {@code default})
 * @param extensions OpenAPI {@code x-*} specification extensions
 * @param security security requirements that override the document-level security, if present
 */
public record Operation(
    String operationId,
    HttpMethod method,
    PathTemplate path,
    Optional<RequestBody> requestBody,
    List<Parameter> parameters,
    Map<String, Response> responses,
    Map<String, Object> extensions,
    Optional<List<SecurityRequirement>> security) {}
