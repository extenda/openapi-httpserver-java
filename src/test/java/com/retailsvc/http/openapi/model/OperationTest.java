package com.retailsvc.http.openapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailsvc.http.openapi.model.OpenApi.Operation;
import com.retailsvc.http.openapi.model.OpenApi.Parameter;
import com.retailsvc.http.openapi.model.OpenApi.Schema;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OperationTest {

  @Nested
  class MatchesPathTest {

    BiFunction<Object, Schema, Boolean> validator = mock();

    @Test
    void shouldFindNamedParameters() {
      when(validator.apply(eq("abc"), any())).thenReturn(true);
      when(validator.apply(eq("Justin"), any())).thenReturn(true);
      when(validator.apply(eq("Case"), any())).thenReturn(true);

      var idSchema = mock(Schema.class);
      var idParameter = new Parameter(null, "path", "ID", true, idSchema);

      var nameSchema = mock(Schema.class);
      var nameParameter = new Parameter(null, "path", "Name", true, nameSchema);

      var surnameSchema = mock(Schema.class);
      var surnameParameter = new Parameter(null, "path", "Surname", true, surnameSchema);
      var parameters = List.of(idParameter, nameParameter, surnameParameter);

      var operation = new Operation("test", null, parameters, null);

      var schemaPath = "/params/path/{ID}/{Name}/{Surname}";
      var requestPath = "/params/path/abc/Justin/Case";
      operation.matchesPath(schemaPath, requestPath, validator);

      verify(validator).apply("abc", idSchema);
      verify(validator).apply("Justin", nameSchema);
      verify(validator).apply("Case", surnameSchema);
    }

    @Test
    void shouldAbortOnDifferentLengths() {
      var operation = spy(new Operation("test", null, List.of(), null));
      var schemaPath = "/params/path/{ID}/{Name}/{Surname}";
      var requestPath = "/params/path/abc";

      var result = operation.matchesPath(schemaPath, requestPath, validator);

      assertThat(result).isFalse();
      verify(operation).hasPathParameters();
      verify(validator, never()).apply(any(), any());
    }

    @Test
    void shouldAbortOnEqualInput() {
      var operation = spy(new Operation("test", null, List.of(), null));
      var schemaPath = "/params/path/abc";
      var requestPath = "/params/path/abc";

      var result = operation.matchesPath(schemaPath, requestPath, validator);

      assertThat(result).isTrue();
      verify(operation, never()).hasPathParameters();
      verify(validator, never()).apply(any(), any());
    }
  }
}
