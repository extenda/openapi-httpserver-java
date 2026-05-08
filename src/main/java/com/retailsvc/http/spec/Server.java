package com.retailsvc.http.spec;

import java.net.URI;

public record Server(String url) {
  public String basePath() {
    return URI.create(url).getPath();
  }
}
