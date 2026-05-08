package com.retailsvc.http.spec;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecRecordsTest {
  private final Schema s = new BooleanSchema(Set.of(TypeName.BOOLEAN));

  @Test
  void parameterLocationEnum() {
    Parameter p = new Parameter("x", Parameter.Location.QUERY, true, s);
    assertThat(p.in()).isEqualTo(Parameter.Location.QUERY);
    assertThat(p.required()).isTrue();
  }

  @Test
  void requestBodyStoresContent() {
    RequestBody body = new RequestBody(true, Map.of("application/json", new MediaType(s)));
    assertThat(body.content()).containsKey("application/json");
    assertThat(body.required()).isTrue();
  }

  @Test
  void serverHasUrl() {
    assertThat(new Server("http://localhost/api").url()).isEqualTo("http://localhost/api");
  }

  @Test
  void infoHasTitleAndVersion() {
    Info i = new Info("test", "1.0.0");
    assertThat(i.title()).isEqualTo("test");
    assertThat(i.version()).isEqualTo("1.0.0");
  }
}
