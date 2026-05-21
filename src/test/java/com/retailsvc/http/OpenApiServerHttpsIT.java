package com.retailsvc.http;

import static com.retailsvc.http.ServerBaseTest.stubAllHandlers;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.Spec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenApiServerHttpsIT {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "rsa, src/test/resources/tls/rsa-cert.pem, src/test/resources/tls/rsa-key.pem",
    "ec,  src/test/resources/tls/ec-cert.pem,  src/test/resources/tls/ec-key.pem"
  })
  void servesHttpsTraffic(String algo, String certPath, String keyPath) throws Exception {
    Path cert = Path.of(certPath);
    Path key = Path.of(keyPath);

    Spec spec;
    try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
      spec = Spec.fromJson(in);
    }

    RequestHandler handler = req -> Response.ok(Map.of("hello", "world"));
    Map<String, RequestHandler> handlers = stubAllHandlers(spec, Map.of("get-data", handler));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(handlers)
            .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
            .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
            .securityValidator("basicAuth", (req, cred) -> Optional.empty())
            .port(0)
            .https(cert, key)
            .build()) {

      HttpClient client =
          HttpClient.newBuilder().version(HTTP_1_1).sslContext(trustStoreFor(cert)).build();

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("https://localhost:" + server.listenPort() + "/api/v1/data"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"hello\":\"world\"");
    }
  }

  @Test
  void negotiatesTls13() throws Exception {
    Path cert = Path.of("src/test/resources/tls/rsa-cert.pem");
    Path key = Path.of("src/test/resources/tls/rsa-key.pem");

    Spec spec;
    try (InputStream in = getClass().getResourceAsStream("/openapi.json")) {
      spec = Spec.fromJson(in);
    }

    RequestHandler handler = req -> Response.ok(Map.of("hello", "world"));
    Map<String, RequestHandler> handlers = stubAllHandlers(spec, Map.of("get-data", handler));

    try (OpenApiServer server =
        OpenApiServer.builder()
            .spec(spec)
            .handlers(handlers)
            .securityValidator("apiKeyAuth", (req, cred) -> Optional.empty())
            .securityValidator("bearerAuth", (req, cred) -> Optional.empty())
            .securityValidator("basicAuth", (req, cred) -> Optional.empty())
            .port(0)
            .https(cert, key)
            .build()) {

      SSLContext clientCtx = trustStoreFor(cert);
      try (SSLSocket socket =
          (SSLSocket) clientCtx.getSocketFactory().createSocket("localhost", server.listenPort())) {
        socket.startHandshake();
        assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
      }
    }
  }

  private static SSLContext trustStoreFor(Path certPath) throws Exception {
    byte[] bytes = Files.readAllBytes(certPath);
    Certificate cert =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(bytes));
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("server", cert);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(new KeyManager[0], tmf.getTrustManagers(), null);
    return ctx;
  }
}
