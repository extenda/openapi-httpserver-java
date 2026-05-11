package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdditionalPropertiesTest {
  @Test
  void allowedIsDefault() {
    AdditionalProperties ap = new AdditionalProperties.Allowed();
    assertThat(ap).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void forbiddenSentinel() {
    assertThat(new AdditionalProperties.Forbidden())
        .isInstanceOf(AdditionalProperties.Forbidden.class);
  }

  @Test
  void schemaConstraintCarriesSchema() {
    Schema inner = new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of());
    AdditionalProperties ap = new AdditionalProperties.SchemaConstraint(inner);
    assertThat(((AdditionalProperties.SchemaConstraint) ap).schema()).isSameAs(inner);
  }
}
