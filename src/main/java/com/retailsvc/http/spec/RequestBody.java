package com.retailsvc.http.spec;

import java.util.Map;

public record RequestBody(boolean required, Map<String, MediaType> content) {}
