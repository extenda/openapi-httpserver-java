package com.retailsvc.http.spec;

import java.util.Map;

public record Info(String title, String version, Map<String, Object> extensions) {}
