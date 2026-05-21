package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemSslContextTest {

  private static final Path RSA_CERT = Path.of("src/test/resources/tls/rsa-cert.pem");
  private static final Path RSA_KEY = Path.of("src/test/resources/tls/rsa-key.pem");
  private static final Path EC_CERT = Path.of("src/test/resources/tls/ec-cert.pem");
  private static final Path EC_KEY = Path.of("src/test/resources/tls/ec-key.pem");

  @Test
  void loadsRsaPemPair() throws Exception {
    SSLContext context = PemSslContext.load(RSA_CERT, RSA_KEY);

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
    assertThat(context.getServerSocketFactory()).isNotNull();
  }

  @Test
  void loadsEcPemPair() throws Exception {
    SSLContext context = PemSslContext.load(EC_CERT, EC_KEY);

    assertThat(context).isNotNull();
    assertThat(context.getServerSocketFactory()).isNotNull();
  }
}
