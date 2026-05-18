package com.retailsvc.http;

/**
 * A credential extracted from a request by the library, handed to a {@link SchemeValidator} for
 * verification. Sealed so consumers can pattern-match across scheme types.
 */
public sealed interface Credential
    permits Credential.ApiKeyCredential, Credential.BearerCredential, Credential.BasicCredential {

  record ApiKeyCredential(String value) implements Credential {}

  record BearerCredential(String token) implements Credential {}

  record BasicCredential(String username, String password) implements Credential {}
}
