package com.retailsvc.http.openapi.model;

/**
 * The 'info' object.
 *
 * @param title The OpenAPI title
 * @param version The version of the OpenAPI specification
 * @see <a href="https://swagger.io/specification/#info-object">Info Object</a>
 */
public record Info(String title, String version) {}
