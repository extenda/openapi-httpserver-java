package com.retailsvc.http.openapi.model;

import java.net.URI;

/**
 * The 'server' object.
 *
 * @param url The server url
 * @see <a href="https://swagger.io/specification/#server-object">Server Object</a>
 */
public record Server(String url) {

  /** Server url without host part */
  public String baseUrl() {
    return URI.create(url).getPath();
  }
}
