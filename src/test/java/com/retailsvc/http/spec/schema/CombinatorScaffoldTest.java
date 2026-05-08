package com.retailsvc.http.spec.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CombinatorScaffoldTest {
  private final Schema s = new BooleanSchema(Set.of(TypeName.BOOLEAN));

  @Test
  void oneOfHoldsOptions() {
    assertThat(new OneOfSchema(List.of(s)).options()).hasSize(1);
  }

  @Test
  void anyOfHoldsOptions() {
    assertThat(new AnyOfSchema(List.of(s)).options()).hasSize(1);
  }

  @Test
  void allOfHoldsParts() {
    assertThat(new AllOfSchema(List.of(s)).parts()).hasSize(1);
  }

  @Test
  void notHoldsSchema() {
    assertThat(new NotSchema(s).schema()).isSameAs(s);
  }

  @Test
  void constHoldsValue() {
    assertThat(new ConstSchema("x").value()).isEqualTo("x");
  }

  @Test
  void enumHoldsValues() {
    assertThat(new EnumSchema(List.of(1, 2)).values()).hasSize(2);
  }

  @Test
  void allCombinatorsTypesEmpty() {
    assertThat(new OneOfSchema(List.of(s)).types()).isEmpty();
    assertThat(new ConstSchema("x").types()).isEmpty();
  }
}
