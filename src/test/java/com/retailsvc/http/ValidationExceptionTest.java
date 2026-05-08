package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.validate.ValidationError;
import org.junit.jupiter.api.Test;

class ValidationExceptionTest {
  @Test
  void carriesError() {
    ValidationError e = new ValidationError("/x", "type", "expected string", null);
    ValidationException ex = new ValidationException(e);
    assertThat(ex.error()).isSameAs(e);
    assertThat(ex.getMessage()).contains("/x").contains("type").contains("expected string");
  }
}
