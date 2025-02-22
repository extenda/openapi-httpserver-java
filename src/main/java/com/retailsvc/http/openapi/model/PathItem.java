package com.retailsvc.http.openapi.model;

import com.retailsvc.http.openapi.model.OpenApi.Operation;

/**
 * The 'path item' represents all the methods/operations available on a path.
 *
 * @see <a href="https://swagger.io/specification/#path-item-object">Path Item Object</a>
 */
public record PathItem(
    Operation head,
    Operation get,
    Operation put,
    Operation post,
    Operation delete,
    Operation connect,
    Operation options,
    Operation trace,
    Operation patch) {

  public Operation findByMethod(String method) {
    return switch (method) {
      case "HEAD" -> head;
      case "GET" -> get;
      case "PUT" -> put;
      case "POST" -> post;
      case "DELETE" -> delete;
      case "CONNECT" -> connect;
      case "OPTIONS" -> options;
      case "TRACE" -> trace;
      case "PATCH" -> patch;
      default -> null;
    };
  }
}
