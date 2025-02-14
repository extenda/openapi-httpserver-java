package com.retailsvc.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorate the `{@link HttpExchange} with the 'body' attribute, holding the request body as a
 * byte-array.
 *
 * @author thced
 */
public class BodyHandler extends Filter {

  public static final String BODY_ATTRIBUTE = "body";

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try (var is = exchange.getRequestBody()) {
      byte[] bytes = is.readAllBytes();
      byte[] bodyBytes = bytes.length > 0 ? bytes : new byte[0];

      chain.doFilter(new RequestBodyWrapper(exchange, bodyBytes));
    }
  }

  @Override
  public String description() {
    return "Body handler";
  }

  public static class RequestBodyWrapper extends HttpExchange {

    private final HttpExchange delegate;
    private final Map<String, Object> attributes;

    public RequestBodyWrapper(HttpExchange exchange, byte[] bodyBytes) {
      this.delegate = exchange;
      this.attributes = new ConcurrentHashMap<>();
      this.attributes.put(BODY_ATTRIBUTE, bodyBytes);
    }

    @Override
    public Headers getRequestHeaders() {
      return delegate.getRequestHeaders();
    }

    @Override
    public Headers getResponseHeaders() {
      return delegate.getResponseHeaders();
    }

    @Override
    public URI getRequestURI() {
      return delegate.getRequestURI();
    }

    @Override
    public String getRequestMethod() {
      return delegate.getRequestMethod();
    }

    @Override
    public HttpContext getHttpContext() {
      return delegate.getHttpContext();
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public InputStream getRequestBody() {
      return delegate.getRequestBody();
    }

    public byte[] getRequestBodyAsBytes() {
      return (byte[]) attributes.get(BODY_ATTRIBUTE);
    }

    @Override
    public OutputStream getResponseBody() {
      return delegate.getResponseBody();
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
      delegate.sendResponseHeaders(rCode, responseLength);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return delegate.getRemoteAddress();
    }

    @Override
    public int getResponseCode() {
      return delegate.getResponseCode();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return delegate.getLocalAddress();
    }

    @Override
    public String getProtocol() {
      return delegate.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    public Object getContextAttribute(String name) {
      return delegate.getAttribute(name);
    }

    public void setContextAttribute(String name, Object value) {
      delegate.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
      delegate.setStreams(i, o);
    }

    @Override
    public HttpPrincipal getPrincipal() {
      return delegate.getPrincipal();
    }
  }
}
