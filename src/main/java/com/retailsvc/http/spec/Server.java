package com.retailsvc.http.spec;

import java.net.URI;

/**
 * OpenAPI {@code server} entry. Only the {@code url} is retained; the first server's path becomes
 * the HTTP context root.
 *
 * @param url full server URL as declared in the OpenAPI document
 */
public record Server(String url) {
  /**
   * Returns the path component of {@link #url()}, used as the HTTP context root.
   *
   * @return the URL's path component
   */
  public String basePath() {
    return URI.create(url).getPath();
  }
}
