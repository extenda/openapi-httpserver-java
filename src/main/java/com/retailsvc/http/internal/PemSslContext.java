package com.retailsvc.http.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Loads a {@link SSLContext} from a PEM certificate chain and PEM PKCS#8 private key. */
public final class PemSslContext {

  private PemSslContext() {}

  public static SSLContext load(Path certChainPem, Path privateKeyPem) {
    Certificate[] chain = readCertificateChain(certChainPem);
    PrivateKey key = readPrivateKey(privateKeyPem);
    return buildSslContext(chain, key);
  }

  private static Certificate[] readCertificateChain(Path path) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read TLS certificate chain: " + path, e);
    }
    return decodeCertificateChain(bytes, path);
  }

  // JEP 524 swap point: replace this body with PEMDecoder when the JDK PEM API lands.
  private static Certificate[] decodeCertificateChain(byte[] pem, Path source) {
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> certs;
      try {
        certs = factory.generateCertificates(new ByteArrayInputStream(pem));
      } catch (CertificateException e) {
        // JDK X509Factory throws "No certificate data found" for input with no
        // BEGIN CERTIFICATE block. Treat that as an empty chain rather than a parse error.
        if (e.getMessage() != null && e.getMessage().contains("No certificate data found")) {
          throw new IllegalStateException(
              "No certificates found in TLS certificate chain: " + source);
        }
        throw e;
      }
      Certificate[] chain = certs.toArray(new Certificate[0]);
      if (chain.length == 0) {
        throw new IllegalStateException(
            "No certificates found in TLS certificate chain: " + source);
      }
      return chain;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse TLS certificate chain from " + source, e);
    }
  }

  private static PrivateKey readPrivateKey(Path path) {
    String pem;
    try {
      pem = Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read TLS private key: " + path, e);
    }
    return decodePrivateKey(pem, path);
  }

  // JEP 524 swap point: replace this body with PEMDecoder when the JDK PEM API lands.
  private static PrivateKey decodePrivateKey(String pem, Path source) {
    byte[] der;
    try {
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      der = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + source, e);
    }
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (InvalidKeySpecException rsaFail) {
      try {
        return KeyFactory.getInstance("EC").generatePrivate(spec);
      } catch (InvalidKeySpecException ecFail) {
        throw new IllegalStateException(
            "Unsupported TLS private key algorithm in " + source, ecFail);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Failed to parse TLS private key from " + source, e);
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to parse TLS private key from " + source, e);
    }
  }

  private static SSLContext buildSslContext(Certificate[] chain, PrivateKey key) {
    verifyKeyMatchesCert(key, chain[0]);
    requireMinimumStrength(key, chain[0]);
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(null, null);
      ks.setKeyEntry("server", key, new char[0], chain);
      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(ks, new char[0]);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), null, null);
      return ctx;
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("TLS certificate and private key do not match", e);
    }
  }

  private static void requireMinimumStrength(PrivateKey key, Certificate cert) {
    PublicKey publicKey = cert.getPublicKey();
    switch (publicKey) {
      case RSAPublicKey rsa -> {
        int bits = rsa.getModulus().bitLength();
        if (bits < 2048) {
          throw new IllegalStateException(
              "TLS RSA key below minimum strength: " + bits + " bits (require >= 2048)");
        }
      }
      case ECPublicKey ec -> {
        int bits = ec.getParams().getCurve().getField().getFieldSize();
        if (bits < 256) {
          throw new IllegalStateException(
              "TLS EC key below minimum strength: " + bits + " bits (require >= 256)");
        }
      }
      default ->
          throw new IllegalStateException(
              "Unsupported TLS public key algorithm: " + publicKey.getAlgorithm());
    }
  }

  private static void verifyKeyMatchesCert(PrivateKey key, Certificate cert) {
    String algorithm =
        switch (key.getAlgorithm()) {
          case "RSA" -> "SHA256withRSA";
          case "EC" -> "SHA256withECDSA";
          default ->
              throw new IllegalStateException(
                  "Unsupported TLS private key algorithm: " + key.getAlgorithm());
        };
    byte[] probe = {1, 2, 3, 4, 5, 6, 7, 8};
    byte[] signature;
    try {
      Signature signer = Signature.getInstance(algorithm);
      signer.initSign(key);
      signer.update(probe);
      signature = signer.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("TLS certificate and private key do not match", e);
    }
    try {
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(cert.getPublicKey());
      verifier.update(probe);
      if (!verifier.verify(signature)) {
        throw new IllegalStateException("TLS certificate and private key do not match");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("TLS certificate and private key do not match", e);
    }
  }
}
