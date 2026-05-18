package com.retailsvc.http.internal;

import com.retailsvc.http.Credential;

record ExtractionResult(Kind kind, Credential credential) {
  enum Kind {
    FOUND,
    MISSING,
    MALFORMED
  }

  static ExtractionResult found(Credential credential) {
    return new ExtractionResult(Kind.FOUND, credential);
  }

  static ExtractionResult missing() {
    return new ExtractionResult(Kind.MISSING, null);
  }

  static ExtractionResult malformed() {
    return new ExtractionResult(Kind.MALFORMED, null);
  }
}
