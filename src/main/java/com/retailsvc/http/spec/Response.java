package com.retailsvc.http.spec;

import java.util.Map;

public record Response(Map<String, MediaType> content) {}
