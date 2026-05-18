package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailsvc.http.Credential;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey;
import com.retailsvc.http.spec.security.SecurityScheme.ApiKey.Location;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBasic;
import com.retailsvc.http.spec.security.SecurityScheme.HttpBearer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialExtractorTest {

  private HttpExchange exchangeWithHeader(String key, String value, String query) {
    HttpExchange ex = mock(HttpExchange.class);
    Headers h = new Headers();
    if (value != null) {
      h.add(key, value);
    }
    when(ex.getRequestHeaders()).thenReturn(h);
    when(ex.getRequestURI())
        .thenReturn(URI.create("http://h/x" + (query == null ? "" : "?" + query)));
    return ex;
  }

  @Test
  void apiKeyHeaderPresentExtracts() {
    var scheme = new ApiKey("X-API-Key", Location.HEADER);
    var ex = exchangeWithHeader("X-API-Key", "abc123", null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.ApiKeyCredential("abc123")));
  }

  @Test
  void apiKeyHeaderMissingReturnsMissing() {
    var scheme = new ApiKey("X-API-Key", Location.HEADER);
    var ex = exchangeWithHeader("Other", "irrelevant", null);
    assertThat(CredentialExtractor.extract(scheme, ex)).isEqualTo(ExtractionResult.missing());
  }

  @Test
  void apiKeyQueryExtracts() {
    var scheme = new ApiKey("k", Location.QUERY);
    var ex = exchangeWithHeader("Ignored", null, "k=v1&other=v2");
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.ApiKeyCredential("v1")));
  }

  @Test
  void httpBearerPresentExtracts() {
    var scheme = new HttpBearer(Optional.empty());
    var ex = exchangeWithHeader("Authorization", "Bearer abc.def.ghi", null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BearerCredential("abc.def.ghi")));
  }

  @Test
  void httpBearerCaseInsensitive() {
    var scheme = new HttpBearer(Optional.empty());
    var ex = exchangeWithHeader("Authorization", "bEaReR token", null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BearerCredential("token")));
  }

  @Test
  void httpBasicValidBase64Extracts() {
    var scheme = new HttpBasic();
    String creds = Base64.getEncoder().encodeToString("alice:s3cret".getBytes());
    var ex = exchangeWithHeader("Authorization", "Basic " + creds, null);
    assertThat(CredentialExtractor.extract(scheme, ex))
        .isEqualTo(ExtractionResult.found(new Credential.BasicCredential("alice", "s3cret")));
  }

  @Test
  void httpBasicMalformedBase64ReturnsMalformed() {
    var scheme = new HttpBasic();
    var ex = exchangeWithHeader("Authorization", "Basic !!!not-base64", null);
    assertThat(CredentialExtractor.extract(scheme, ex)).isEqualTo(ExtractionResult.malformed());
  }
}
