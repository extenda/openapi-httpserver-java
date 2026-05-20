package com.retailsvc.http;

/**
 * A credential extracted from a request by the library, handed to a {@link SchemeValidator} for
 * verification. Sealed so consumers can pattern-match across scheme types.
 */
public sealed interface Credential
    permits Credential.ApiKeyCredential, Credential.BearerCredential, Credential.BasicCredential {

  /**
   * Credential extracted from an OpenAPI {@code apiKey} security scheme (header, query, or cookie).
   *
   * @param value raw key value as presented by the client.
   */
  record ApiKeyCredential(String value) implements Credential {}

  /**
   * Credential extracted from an {@code http} security scheme with {@code bearer} style.
   *
   * @param token raw bearer token (no {@code Bearer } prefix).
   */
  record BearerCredential(String token) implements Credential {}

  /**
   * Credential extracted from an {@code http} security scheme with {@code basic} style.
   *
   * @param username decoded username portion.
   * @param password decoded password portion.
   */
  record BasicCredential(String username, String password) implements Credential {}
}
