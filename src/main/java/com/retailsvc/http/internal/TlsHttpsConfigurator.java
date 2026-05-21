package com.retailsvc.http.internal;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Pins HTTPS to TLS 1.2 and 1.3 only, regardless of operator-level {@code java.security} overrides,
 * and explicitly leaves client-cert auth off (no mTLS in v1).
 */
public final class TlsHttpsConfigurator extends HttpsConfigurator {
  public TlsHttpsConfigurator(SSLContext context) {
    super(context);
  }

  @Override
  public void configure(HttpsParameters params) {
    SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
    sslParams.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    sslParams.setNeedClientAuth(false);
    sslParams.setWantClientAuth(false);
    params.setSSLParameters(sslParams);
  }
}
