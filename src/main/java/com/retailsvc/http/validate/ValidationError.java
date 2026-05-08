package com.retailsvc.http.validate;

public record ValidationError(
    String pointer, String keyword, String message, Object rejectedValue) {}
