package com.retailsvc.http;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityIT extends ServerBaseTest {

  @Test
  void apiKeyAllowDenyAndMissing() throws Exception {
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(defaultHandlers())
            .securityValidator(
                "apiKeyAuth",
                (req, cred) ->
                    cred instanceof Credential.ApiKeyCredential ak && "good".equals(ak.value())
                        ? Optional.of("api-principal")
                        : Optional.empty())
            .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
            .securityValidator("basicAuth", (req, cred) -> Optional.empty())
            .port(0)
            .build();

    var client = httpClient();

    var ok =
        client.send(
            newRequest(server, "/secure/api-key", "GET", noBody(), Map.of("X-API-Key", "good")),
            ofString());
    assertThat(ok.statusCode()).isEqualTo(200);

    var missing =
        client.send(newRequest(server, "/secure/api-key", "GET", noBody(), Map.of()), ofString());
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(missing.headers().firstValue("WWW-Authenticate"))
        .contains("ApiKey location=header, name=\"X-API-Key\"");
    assertThat(missing.body()).contains("\"status\":401");

    var denied =
        client.send(
            newRequest(server, "/secure/api-key", "GET", noBody(), Map.of("X-API-Key", "bad")),
            ofString());
    assertThat(denied.statusCode()).isEqualTo(403);
    assertThat(denied.headers().firstValue("WWW-Authenticate")).isEmpty();
  }

  @Test
  void bearerAllowAndMissing() throws Exception {
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(defaultHandlers())
            .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
            .securityValidator(
                "bearerAuth",
                (req, cred) ->
                    cred instanceof Credential.BearerCredential bc
                            && "good-token".equals(bc.token())
                        ? Optional.of("bearer-principal")
                        : Optional.empty())
            .securityValidator("basicAuth", (req, cred) -> Optional.empty())
            .port(0)
            .build();

    var client = httpClient();

    var ok =
        client.send(
            newRequest(
                server,
                "/secure/bearer",
                "GET",
                noBody(),
                Map.of("Authorization", "Bearer good-token")),
            ofString());
    assertThat(ok.statusCode()).isEqualTo(200);

    var missing =
        client.send(newRequest(server, "/secure/bearer", "GET", noBody(), Map.of()), ofString());
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(missing.headers().firstValue("WWW-Authenticate")).contains("Bearer realm=\"api\"");
  }

  @Test
  void basicAuthAllow() throws Exception {
    String creds = Base64.getEncoder().encodeToString("alice:s3cret".getBytes());
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(defaultHandlers())
            .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
            .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
            .securityValidator(
                "basicAuth",
                (req, cred) ->
                    cred instanceof Credential.BasicCredential bc
                            && "alice".equals(bc.username())
                            && "s3cret".equals(bc.password())
                        ? Optional.of("basic-principal")
                        : Optional.empty())
            .port(0)
            .build();

    var ok =
        httpClient()
            .send(
                newRequest(
                    server,
                    "/secure/basic",
                    "GET",
                    noBody(),
                    Map.of("Authorization", "Basic " + creds)),
                ofString());
    assertThat(ok.statusCode()).isEqualTo(200);
  }

  @Test
  void externalAuthBypassesAllChecks() throws Exception {
    server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(defaultHandlers())
            .useExternalAuthentication()
            .port(0)
            .build();

    var r =
        httpClient()
            .send(newRequest(server, "/secure/api-key", "GET", noBody(), Map.of()), ofString());
    assertThat(r.statusCode()).isEqualTo(200);
  }

  private static Map<String, RequestHandler> defaultHandlers() {
    return Map.of(
        "secureApiKey", req -> Response.ok("{\"ok\":true}"),
        "secureBearer", req -> Response.ok("{\"ok\":true}"),
        "secureBasic", req -> Response.ok("{\"ok\":true}"),
        "secureOpen", req -> Response.ok("{\"ok\":true}"));
  }
}
