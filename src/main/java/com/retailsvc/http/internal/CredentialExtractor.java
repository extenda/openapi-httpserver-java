package com.retailsvc.http.internal;

import com.retailsvc.http.Credential;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class CredentialExtractor {
  private CredentialExtractor() {}

  static ExtractionResult extract(SecurityScheme scheme, HttpExchange exchange) {
    return switch (scheme) {
      case ApiKey ak -> extractApiKey(ak, exchange);
      case HttpBearer _ -> extractBearer(exchange);
      case HttpBasic _ -> extractBasic(exchange);
      case SecurityScheme.Unsupported _ ->
          throw new IllegalStateException(
              "extractor called with Unsupported scheme — should be caught at boot");
    };
  }

  private static ExtractionResult extractApiKey(ApiKey scheme, HttpExchange exchange) {
    String value =
        switch (scheme.location()) {
          case HEADER -> exchange.getRequestHeaders().getFirst(scheme.name());
          case QUERY -> firstQueryValue(exchange.getRequestURI().getRawQuery(), scheme.name());
          case COOKIE -> firstCookieValue(exchange, scheme.name());
        };
    return value == null
        ? ExtractionResult.missing()
        : ExtractionResult.found(new Credential.ApiKeyCredential(value));
  }

  private static ExtractionResult extractBearer(HttpExchange exchange) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null) {
      return ExtractionResult.missing();
    }
    String[] parts = auth.split("\\s+", 2);
    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
      return ExtractionResult.missing();
    }
    return ExtractionResult.found(new Credential.BearerCredential(parts[1]));
  }

  private static ExtractionResult extractBasic(HttpExchange exchange) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth == null) {
      return ExtractionResult.missing();
    }
    String[] parts = auth.split("\\s+", 2);
    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Basic")) {
      return ExtractionResult.missing();
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(parts[1]);
    } catch (IllegalArgumentException _) {
      return ExtractionResult.malformed();
    }
    String creds = new String(decoded, StandardCharsets.UTF_8);
    int sep = creds.indexOf(':');
    if (sep < 0) {
      return ExtractionResult.malformed();
    }
    return ExtractionResult.found(
        new Credential.BasicCredential(creds.substring(0, sep), creds.substring(sep + 1)));
  }

  private static String firstQueryValue(String rawQuery, String name) {
    if (rawQuery == null) {
      return null;
    }
    String prefix = name + "=";
    for (String pair : rawQuery.split("&")) {
      if (pair.startsWith(prefix)) {
        return URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String firstCookieValue(HttpExchange exchange, String name) {
    String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
    if (cookieHeader == null) {
      return null;
    }
    for (String pair : cookieHeader.split(";")) {
      String trimmed = pair.trim();
      if (trimmed.startsWith(name + "=")) {
        return trimmed.substring(name.length() + 1);
      }
    }
    return null;
  }
}
