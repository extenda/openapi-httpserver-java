package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationTest {
  @Test
  void operationCarriesAllFields() {
    var path = PathTemplate.compile("/users/{id}");
    var param =
        new Parameter(
            "id",
            Parameter.Location.PATH,
            true,
            new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of()));
    Operation op =
        new Operation(
            "get-user", HttpMethod.GET, path, Optional.empty(), List.of(param), Map.of(), Map.of());
    assertThat(op.operationId()).isEqualTo("get-user");
    assertThat(op.method()).isEqualTo(HttpMethod.GET);
    assertThat(op.parameters()).hasSize(1);
  }
}
