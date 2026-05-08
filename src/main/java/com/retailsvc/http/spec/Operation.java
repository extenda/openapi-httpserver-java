package com.retailsvc.http.spec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Operation(
    String operationId,
    HttpMethod method,
    PathTemplate path,
    Optional<RequestBody> requestBody,
    List<Parameter> parameters,
    Map<String, Response> responses) {}
