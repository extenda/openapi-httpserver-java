package com.retailsvc.http.validate;

/**
 * A single validation failure produced while checking a request against an OpenAPI schema.
 *
 * @param pointer JSON Pointer to the offending location in the request payload
 * @param keyword the JSON Schema keyword that failed (e.g. {@code "required"}, {@code "type"})
 * @param message human-readable description of the failure
 * @param rejectedValue the value that failed validation, or {@code null} if not applicable
 */
public record ValidationError(
    String pointer, String keyword, String message, Object rejectedValue) {}
