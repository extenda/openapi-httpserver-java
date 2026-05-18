package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityBuilderTest {

  @Test
  void securityValidatorRequiresNonNullName() {
    var builder = OpenApiServer.builder();
    assertThatThrownBy(() -> builder.securityValidator(null, (r, c) -> Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("schemeName");
  }

  @Test
  void securityValidatorRequiresNonNullValidator() {
    var builder = OpenApiServer.builder();
    assertThatThrownBy(() -> builder.securityValidator("x", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("validator");
  }

  @Test
  void useExternalAuthenticationIsFluent() {
    var builder = OpenApiServer.builder();
    var returned = builder.useExternalAuthentication();
    assertThat(returned).isSameAs(builder);
  }
}
