package com.retailsvc.http.spec.security;

import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.retailsvc.http.spec.security.SecurityScheme.Unsupported;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SecuritySchemeParser {
  private SecuritySchemeParser() {}

  public static SecurityScheme parse(Map<String, Object> raw) {
    String type = (String) raw.get("type");
    if (type == null) {
      throw new IllegalArgumentException("securityScheme missing required 'type'");
    }
    return switch (type) {
      case "apiKey" -> parseApiKey(raw);
      case "http" -> parseHttp(raw);
      default -> new Unsupported(type);
    };
  }

  private static SecurityScheme parseApiKey(Map<String, Object> raw) {
    String name = (String) raw.get("name");
    String in = (String) raw.get("in");
    if (name == null || in == null) {
      throw new IllegalArgumentException("apiKey scheme requires 'name' and 'in'");
    }
    return new ApiKey(name, Location.valueOf(in.toUpperCase(Locale.ROOT)));
  }

  private static SecurityScheme parseHttp(Map<String, Object> raw) {
    String scheme = (String) raw.get("scheme");
    if (scheme == null) {
      throw new IllegalArgumentException("http securityScheme requires 'scheme'");
    }
    return switch (scheme.toLowerCase(Locale.ROOT)) {
      case "bearer" -> new HttpBearer(Optional.ofNullable((String) raw.get("bearerFormat")));
      case "basic" -> new HttpBasic();
      default -> new Unsupported("http:" + scheme);
    };
  }
}
