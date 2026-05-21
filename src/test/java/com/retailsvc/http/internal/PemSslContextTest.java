package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
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
  private static final Path WEAK_RSA_CERT = Path.of("src/test/resources/tls/weak-rsa-cert.pem");
  private static final Path WEAK_RSA_KEY = Path.of("src/test/resources/tls/weak-rsa-key.pem");
  private static final Path WEAK_EC_CERT = Path.of("src/test/resources/tls/weak-ec-cert.pem");
  private static final Path WEAK_EC_KEY = Path.of("src/test/resources/tls/weak-ec-key.pem");
  private static final Path DSA_CERT = Path.of("src/test/resources/tls/dsa-cert.pem");
  private static final Path DSA_KEY = Path.of("src/test/resources/tls/dsa-key.pem");

  @Test
  void loadsRsaPemPair() {
    SSLContext context = PemSslContext.load(RSA_CERT, RSA_KEY);

    assertThat(context).isNotNull();
    assertThat(context.getProtocol()).isEqualTo("TLS");
    assertThat(context.getServerSocketFactory()).isNotNull();
  }

  @Test
  void loadsEcPemPair() {
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

  @Test
  void rejectsEmptyCertificateChain() throws Exception {
    Path emptyCert = Files.createTempFile("empty-chain", ".pem");
    Files.writeString(emptyCert, "# no certificates here\n");
    try {
      assertThatThrownBy(() -> PemSslContext.load(emptyCert, RSA_KEY))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No certificates found in TLS certificate chain");
    } finally {
      Files.deleteIfExists(emptyCert);
    }
  }

  @Test
  void acceptsEcKeyAtMinimumStrength() {
    // P-256 (256 bits) is exactly at the floor — must pass.
    SSLContext ctx = PemSslContext.load(EC_CERT, EC_KEY);
    assertThat(ctx).isNotNull();
  }

  @Test
  void rejectsWeakRsaKey() {
    assertThatThrownBy(() -> PemSslContext.load(WEAK_RSA_CERT, WEAK_RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS RSA key below minimum strength")
        .hasMessageContaining("1024 bits");
  }

  @Test
  void rejectsWeakEcKey() {
    assertThatThrownBy(() -> PemSslContext.load(WEAK_EC_CERT, WEAK_EC_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TLS EC key below minimum strength")
        .hasMessageContaining("192 bits");
  }

  @Test
  void rejectsUnsupportedKeyAlgorithm() {
    assertThatThrownBy(() -> PemSslContext.load(RSA_CERT, DSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported TLS private key algorithm");
  }

  @Test
  void rejectsUnsupportedCertAlgorithm() {
    assertThatThrownBy(() -> PemSslContext.load(DSA_CERT, RSA_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported TLS public key algorithm");
  }
}
