package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemSslContextTest {

  private static final Path RSA_CERT = Path.of("src/test/resources/tls/rsa-cert.pem");
  private static final Path RSA_KEY = Path.of("src/test/resources/tls/rsa-key.pem");
  private static final Path EC_CERT = Path.of("src/test/resources/tls/ec-cert.pem");
  private static final Path EC_KEY = Path.of("src/test/resources/tls/ec-key.pem");
  private static final Path MISMATCHED_KEY = Path.of("src/test/resources/tls/mismatched-key.pem");
  private static final Path GARBAGE = Path.of("src/test/resources/tls/garbage.pem");
  private static final Path MISSING = Path.of("src/test/resources/tls/does-not-exist.pem");

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

  @Test
  void rejectsMissingCertFile() {
    assertThatThrownBy(() -> PemSslContext.load(MISSING, RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read TLS certificate chain")
        .hasMessageContaining("does-not-exist.pem");
  }

  @Test
  void rejectsMissingKeyFile() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, MISSING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read TLS private key")
        .hasMessageContaining("does-not-exist.pem");
  }

  @Test
  void rejectsGarbageCertPem() {
    assertThatThrownBy(() -> PemSslContext.load(GARBAGE, RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse TLS certificate chain");
  }

  @Test
  void rejectsGarbageKeyPem() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, GARBAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse TLS private key");
  }

  @Test
  void rejectsMismatchedCertAndKey() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, MISMATCHED_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match");
  }
}
