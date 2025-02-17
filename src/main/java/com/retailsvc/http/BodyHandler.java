package com.retailsvc.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorate the `{@link HttpExchange} with the 'body' attribute, holding the request body as a
 * byte-array.
 *
 * @author thced
 */
public class BodyHandler extends Filter {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** The key to the attributes map where the request body is stored */
  public static final String BODY_ATTRIBUTE = "body";

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    try (var is = exchange.getRequestBody()) {
      byte[] bytes = is.readAllBytes();
      chain.doFilter(new RequestBodyWrapper(exchange, bytes));
    }
  }

  @Override
  public String description() {
    return "Body handler";
  }

  /**
   * Delegate to support get/set of private attributes on the HttpExchange. It also caches the
   * request body for later use, as the inputstream of the request body can only be read once.
   */
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
      if (value == null) {
        LOG.warn("Not allowed to insert a null value for attribute '{}'. Skipping..", name);
        return;
      }
      attributes.put(name, value);
    }

    /**
     * Custom method to access the delegate's attributes.
     *
     * <p><em>Note that these attributes are shared by all {@link HttpExchange} on the same {@link
     * HttpContext}. For attributes private to the request scope, use {@link #getAttribute(String)}
     * and {@link #setAttribute(String, Object)}.</em>
     *
     * @param name Name of the attribute
     * @return The attribute, or null if not found
     */
    public Object getContextAttribute(String name) {
      return delegate.getAttribute(name);
    }

    /**
     * Custom method to add a key-value pair to the shared attributes.
     *
     * @param name The name of the attribute
     * @param value The value to add
     * @see #getContextAttribute(String)
     */
    public void setContextAttribute(String name, Object value) {
      if (value == null) {
        LOG.warn("Not allowed to insert a null value for shared attribute '{}'. Skipping..", name);
        return;
      }
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
