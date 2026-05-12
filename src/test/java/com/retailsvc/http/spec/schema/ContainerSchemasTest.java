package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContainerSchemasTest {
  @Test
  void objectSchemaCarriesPropertiesAndRequired() {
    Schema name = new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null, Map.of());
    ObjectSchema o =
        new ObjectSchema(
            Set.of(TypeName.OBJECT),
            Map.of("name", name),
            List.of("name"),
            new AdditionalProperties.Allowed(),
            null,
            null,
            Map.of());
    assertThat(o.properties()).containsKey("name");
    assertThat(o.required()).containsExactly("name");
    assertThat(o.additionalProperties()).isInstanceOf(AdditionalProperties.Allowed.class);
  }

  @Test
  void arraySchemaCarriesItemsAndConstraints() {
    Schema items =
        new IntegerSchema(
            Set.of(TypeName.INTEGER), null, null, null, null, null, "int32", Map.of());
    ArraySchema a = new ArraySchema(Set.of(TypeName.ARRAY), items, 1, 10, true, Map.of());
    assertThat(a.items()).isSameAs(items);
    assertThat(a.minItems()).isEqualTo(1);
    assertThat(a.maxItems()).isEqualTo(10);
    assertThat(a.uniqueItems()).isTrue();
  }
}
