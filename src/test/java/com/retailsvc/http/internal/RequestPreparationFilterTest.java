package com.retailsvc.http.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.retailsvc.http.MethodNotAllowedException;
import com.retailsvc.http.NotFoundException;
import com.retailsvc.http.Request;
import com.retailsvc.http.TypeMapper;
import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Info;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.Parameter;
import com.retailsvc.http.spec.PathTemplate;
import com.retailsvc.http.spec.Server;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import com.retailsvc.http.validate.DefaultValidator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RequestPreparationFilterTest {

  private HttpExchange exchange(String method, String path, byte[] body) {
    HttpExchange ex = mock(HttpExchange.class);
    Mockito.when(ex.getRequestMethod()).thenReturn(method);
    Mockito.when(ex.getRequestURI()).thenReturn(URI.create(path));
    Mockito.when(ex.getRequestHeaders()).thenReturn(new Headers());
    Mockito.when(ex.getRequestBody()).thenReturn(new ByteArrayInputStream(body));
    return ex;
  }

  private Spec specWith(Operation... ops) {
    return new Spec(
        "3.1.0",
        new Info("t", "1", Map.of()),
        List.of(new Server("/")),
        List.of(ops),
        Map.of(),
        Map.of(),
        "",
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of());
  }

  private Filter newFilter(Spec spec) {
    TypeMapper textMapper =
        new TypeMapper() {
          @Override
          public Object readFrom(byte[] body, String contentTypeHeader) {
            return new String(body, StandardCharsets.UTF_8);
          }

          @Override
          public byte[] writeTo(Object value) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
          }
        };
    Map<String, TypeMapper> mappers = Map.of("application/json", textMapper);
    return new RequestPreparationFilter(
        spec, new Router(spec.operations()), new DefaultValidator(spec::resolveSchema), mappers);
  }

  @Test
  void successPathBindsRequestContextDuringChain() throws Exception {
    var op =
        new Operation(
            "get-user",
            HttpMethod.GET,
            PathTemplate.compile("/users/{id}"),
            Optional.empty(),
            List.of(),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);
    HttpExchange ex = exchange("GET", "/users/42", new byte[0]);

    AtomicReference<String> seenOpId = new AtomicReference<>();
    AtomicReference<Map<String, String>> seenPathParams = new AtomicReference<>();

    Filter.Chain chain = mock(Filter.Chain.class);
    Mockito.doAnswer(
            inv -> {
              Request req = DispatchHandler.CURRENT.get();
              seenOpId.set(req.operationId());
              seenPathParams.set(req.pathParams());
              return null;
            })
        .when(chain)
        .doFilter(Mockito.any());

    f.doFilter(ex, chain);

    assertThat(seenOpId.get()).isEqualTo("get-user");
    assertThat(seenPathParams.get()).containsEntry("id", "42");
    Mockito.verify(chain).doFilter(ex);
  }

  @Test
  void unknownPathThrowsNotFound() {
    Spec spec =
        specWith(
            new Operation(
                "a",
                HttpMethod.GET,
                PathTemplate.compile("/x"),
                Optional.empty(),
                List.of(),
                Map.of(),
                Map.of()));
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/missing", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void wrongMethodThrowsMethodNotAllowed() {
    Spec spec =
        specWith(
            new Operation(
                "a",
                HttpMethod.GET,
                PathTemplate.compile("/x"),
                Optional.empty(),
                List.of(),
                Map.of(),
                Map.of()));
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("POST", "/x", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(MethodNotAllowedException.class);
  }

  @Test
  void invalidQueryParamThrowsValidation() {
    var stringSchema =
        new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null, Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("q", Parameter.Location.QUERY, true, stringSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?q=ab", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().pointer())
        .isEqualTo("/query/q");
  }

  @Test
  void integerQueryParamIsCoercedFromStringBeforeValidation() throws Exception {
    var intSchema =
        new IntegerSchema(Set.of(TypeName.INTEGER), 1L, 100L, null, null, null, null, Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("n", Parameter.Location.QUERY, true, intSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?n=42", new byte[0]);
    Filter.Chain chain = mock(Filter.Chain.class);
    f.doFilter(ex, chain);
    Mockito.verify(chain).doFilter(ex);
  }

  @Test
  void integerQueryParamRejectsNonNumericString() {
    var intSchema =
        new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, null, Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("n", Parameter.Location.QUERY, true, intSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?n=abc", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void numberQueryParamIsCoercedFromStringBeforeValidation() throws Exception {
    var numSchema =
        new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, null, Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("n", Parameter.Location.QUERY, true, numSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?n=1.5", new byte[0]);
    Filter.Chain chain = mock(Filter.Chain.class);
    f.doFilter(ex, chain);
    Mockito.verify(chain).doFilter(ex);
  }

  @Test
  void numberQueryParamRejectsNonNumericString() {
    var numSchema =
        new NumberSchema(Set.of(TypeName.NUMBER), null, null, null, null, null, null, Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("n", Parameter.Location.QUERY, true, numSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?n=abc", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void booleanQueryParamCoercesTrueAndFalse() throws Exception {
    var boolSchema = new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("b", Parameter.Location.QUERY, true, boolSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    Filter.Chain trueChain = mock(Filter.Chain.class);
    Filter.Chain falseChain = mock(Filter.Chain.class);
    HttpExchange trueEx = exchange("GET", "/x?b=true", new byte[0]);
    HttpExchange falseEx = exchange("GET", "/x?b=false", new byte[0]);
    f.doFilter(trueEx, trueChain);
    f.doFilter(falseEx, falseChain);
    Mockito.verify(trueChain).doFilter(trueEx);
    Mockito.verify(falseChain).doFilter(falseEx);
  }

  @Test
  void booleanQueryParamRejectsNonBooleanString() {
    var boolSchema = new BooleanSchema(Set.of(TypeName.BOOLEAN), Map.of());
    var op =
        new Operation(
            "a",
            HttpMethod.GET,
            PathTemplate.compile("/x"),
            Optional.empty(),
            List.of(new Parameter("b", Parameter.Location.QUERY, true, boolSchema)),
            Map.of(),
            Map.of());
    Spec spec = specWith(op);
    Filter f = newFilter(spec);

    HttpExchange ex = exchange("GET", "/x?b=yes", new byte[0]);
    assertThatThrownBy(() -> f.doFilter(ex, mock(Filter.Chain.class)))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }
}
