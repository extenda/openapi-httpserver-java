package com.retailsvc.http.spec;

import java.util.Map;

/**
 * OpenAPI {@code info} object describing the API's identity.
 *
 * @param title human-readable API title
 * @param version API version string
 * @param extensions OpenAPI {@code x-*} specification extensions
 */
public record Info(String title, String version, Map<String, Object> extensions) {}
