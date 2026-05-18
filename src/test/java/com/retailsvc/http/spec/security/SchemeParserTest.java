package com.retailsvc.http.spec.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemeParserTest {

  @Test
  void apiKeyHeaderParses() {
    var scheme =
        SecuritySchemeParser.parse(Map.of("type", "apiKey", "name", "X-API-Key", "in", "header"));
    assertThat(scheme).isEqualTo(new ApiKey("X-API-Key", Location.HEADER));
  }

  @Test
  void httpBearerParses() {
    var scheme =
        SecuritySchemeParser.parse(
            Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
    assertThat(scheme).isEqualTo(new SecurityScheme.HttpBearer(Optional.of("JWT")));
  }

  @Test
  void httpBasicParses() {
    var scheme = SecuritySchemeParser.parse(Map.of("type", "http", "scheme", "basic"));
    assertThat(scheme).isEqualTo(new SecurityScheme.HttpBasic());
  }

  @Test
  void unknownTypeMapsToUnsupported() {
    var scheme = SecuritySchemeParser.parse(Map.of("type", "oauth2"));
    assertThat(scheme).isEqualTo(new SecurityScheme.Unsupported("oauth2"));
  }
}
