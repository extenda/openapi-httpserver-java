package com.retailsvc.http;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.fail;

import com.retailsvc.http.start.EchoHandler;
import com.retailsvc.http.start.GetDataHandler;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OpenApiServerIT extends ServerBaseTest {

  @Test
  void serverStart() {
    try (var server = newServer(Map.of())) {
      assertDoesNotThrow(server::close);
    }
  }

  @Nested
  class Data {

    String path = "/data";

    @Test
    void getDataShouldReturnJsonBody() {
      try (var server = newServer(Map.of("get-data", new GetDataHandler()));
          var client = httpClient()) {

        var headers = Map.of("x-name", "Alotta");
        var request = newRequest(server, path, "GET", noBody(), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEqualToIgnoringWhitespace("{\"id\":\"some-id\"}");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void getDataShouldReturnBadRequestOnInvalidXNameHeader() {
      try (var server = newServer(Map.of("get-data", new GetDataHandler()));
          var client = httpClient()) {

        var headers = Map.of("x-name", "invalid-header");
        var request = newRequest(server, path, "GET", noBody(), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("keyword");
        assertThat(responseBody).contains("pointer");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postDataShouldReturnJsonBody() {
      try (var server = newServer(Map.of("post-data", new EchoHandler()));
          var client = httpClient()) {

        // language=JSON
        var body =
            """
            {
              "id": "some-id",
              "age": 42,
              "random": "d5af5004-8b5a-4db6-838e-38be773eac34",
              "status": "ERROR",
              "feelingGood": true,
              "aList": [ "string", "string" ],
              "anObject": {
                "id": "some-id",
                "age": 42,
                "longNumber": 900,
                "nested": {
                  "nestedValue": 43
                }
              },
              "aListOfObjects": [
                { "value": 42 },
                { "value": 43 }
              ],
              "aDate": "2025-03-02",
              "aDateTime": "2025-03-02T12:34:56Z"
            }\
            """;
        var headers = Map.of("correlation-id", UUID.randomUUID().toString());
        var request = newRequest(server, path, "POST", ofString(body), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEqualToIgnoringWhitespace(body);

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postDataShouldReturnBadRequestOnMissingRequiredProperties() {
      Map<String, RequestHandler> handlers = Map.of("post-data", new EchoHandler());

      try (var server = newServer(handlers);
          var client = httpClient()) {

        // language=JSON
        var body =
            """
            {
              "id": "some-id",
              "age": 42,
              "random": "d5af5004-8b5a-4db6-838e-38be773eac34",
              "status": "ERROR",
              "anObject": {
                "id": "some-id",
                "age": 42,
                "longNumber": 900,
                "nested": {
                  "nestedValue": 43
                }
              },
              "aListOfObjects": [
                { "value": 42 },
                { "value": 43 }
              ],
              "aDate": "2025-03-02",
              "aDateTime": "2025-03-02T12:34:56Z"
            }\
            """;
        var headers = Map.of("correlation-id", UUID.randomUUID().toString());
        var request = newRequest(server, path, "POST", ofString(body), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("keyword");
        assertThat(responseBody).contains("pointer");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class ListObjects {

    String path = "/list/objects";

    @Test
    void listObjectsShouldReturnJsonBody() {
      try (var server = newServer(Map.of("post-list-objects", new EchoHandler()));
          var client = httpClient()) {

        // language=JSON
        var body =
            """
            [
              { "value": 42 },
              { "value": 43 }
            ]
            """;
        var headers = Map.of("correlation-id", UUID.randomUUID().toString());
        var request = newRequest(server, path, "POST", ofString(body), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEqualToIgnoringWhitespace("[{\"value\":42},{\"value\":43}]");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void listObjectsShouldReturnBadRequestOnPassingObjectInsteadOfArray() {
      try (var server = newServer(Map.of("post-list-objects", new EchoHandler()));
          var client = httpClient()) {

        // language=JSON
        var body = "{\"value\":42}";
        var headers = Map.of("correlation-id", UUID.randomUUID().toString());
        var request = newRequest(server, path, "POST", ofString(body), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("keyword");
        assertThat(responseBody).contains("pointer");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class QueryParams {

    String path = "/params/query";

    @Test
    void getParamsQueryShouldReturnOkOnValidQueryParams() {
      try (var server = newServer(Map.of("query-params", new EchoHandler()));
          var client = httpClient()) {

        var pathWithParams = path + "?q1=data&q2=data";
        var headers = Map.of("correlation-id", UUID.randomUUID().toString());
        var request = newRequest(server, pathWithParams, "GET", noBody(), headers);

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEmpty();

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void paramsQueryShouldReturnBadRequestOnMissingRequiredQueryParams() {
      try (var server = newServer(Map.of("query-params", new EchoHandler()));
          var client = httpClient()) {

        // missing 'q2=data'
        var pathWithParams = path + "?q1=data";
        var request = newRequest(server, pathWithParams, "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("keyword");
        assertThat(responseBody).contains("pointer");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class PathParams {

    String path = "/params/path";

    @Test
    void getPathParamsShouldReturnOkOnValidPathParam() {
      try (var server = newServer(Map.of("path-params", new EchoHandler()));
          var client = httpClient()) {

        var pathWithParams = path + "/1234567890";
        var request = newRequest(server, pathWithParams, "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEmpty();

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void getPathParamsShouldReturnOkOnMultipleValidPathParams() {
      try (var server = newServer(Map.of("path-params-multi", new EchoHandler()));
          var client = httpClient()) {

        var pathWithParams = path + "/1234567890/Justin/Case";
        var request = newRequest(server, pathWithParams, "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(200);
        assertThat(responseBody).isEmpty();

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void getPathParamsShouldReturnBadRequestOnBadFormatPathParam() {
      try (var server = newServer(Map.of("path-params-multi", new EchoHandler()));
          var client = httpClient()) {

        // '123' does not match pattern [A-Za-z]+ for Name parameter
        var pathWithParams = path + "/1234567890/123/Case";
        var request = newRequest(server, pathWithParams, "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("keyword");
        assertThat(responseBody).contains("pointer");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void getPathParamsShouldReturnInternalErrorOnMissingHandler() {
      try (var server = newServer(Map.of("not-a-valid-operation-id", new EchoHandler()));
          var client = httpClient()) {

        var pathWithParams = path + "/1234567890/Justin/Case";
        var request = newRequest(server, pathWithParams, "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();

        assertThat(statusCode).isEqualTo(500);

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class Shapes {

    String path = "/shapes";

    @Test
    void postShapeValidCircleReturns200() {
      try (var server = newServer(Map.of("post-shape", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"kind\":\"circle\",\"radius\":2.5}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"kind\":\"circle\"");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postShapeValidSquareReturns200() {
      try (var server = newServer(Map.of("post-shape", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"kind\":\"square\",\"side\":3}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postShapeUnknownKindReturns400() {
      // matches zero branches: "kind" is neither "circle" nor "square".
      try (var server = newServer(Map.of("post-shape", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"kind\":\"triangle\",\"side\":3}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/problem+json");
        assertThat(response.body()).contains("oneOf");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postShapeMissingDiscriminatorReturns400() {
      // omitting "kind" makes both branches fail "required".
      try (var server = newServer(Map.of("post-shape", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"radius\":2.5}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/problem+json");
        assertThat(response.body()).contains("oneOf");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class Filters {

    String path = "/filters";

    @Test
    void postFilterValidStringValueReturns200() {
      try (var server = newServer(Map.of("post-filter", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"value\":\"abcd\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postFilterValidIntegerValueReturns200() {
      try (var server = newServer(Map.of("post-filter", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"value\":42}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postFilterShortStringMatchesNoBranchReturns400() {
      // "ab" has length < 3 (string branch fails) and is not an integer (integer branch fails).
      try (var server = newServer(Map.of("post-filter", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"value\":\"ab\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/problem+json");
        assertThat(response.body()).contains("anyOf");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class Blocked {

    String path = "/blocked";

    @Test
    void postBlockedAcceptedTokenReturns200() {
      try (var server = newServer(Map.of("post-blocked", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"token\":\"allowed\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postBlockedForbiddenTokenReturns400() {
      try (var server = newServer(Map.of("post-blocked", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"token\":\"forbidden\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/problem+json");
        assertThat(response.body()).contains("not");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class FormatEmail {

    String path = "/format/email";

    @Test
    void formatEmailShouldReturnBadRequestOnInvalidEmail() {
      try (var server = newServer(Map.of("format-email", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?addr=not-an-email", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("\"format\"");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void formatEmailShouldReturnOkOnValidEmail() {
      try (var server = newServer(Map.of("format-email", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?addr=user%40example.com", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();

        assertThat(statusCode).isEqualTo(200);

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class FormatByte {

    String path = "/format/byte";

    @Test
    void formatByteShouldReturnBadRequestOnInvalidBase64() {
      try (var server = newServer(Map.of("format-byte", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?data=not%20base64!!", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("\"format\"");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void formatByteShouldReturnOkOnValidBase64() {
      try (var server = newServer(Map.of("format-byte", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?data=aGVsbG8%3D", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();

        assertThat(statusCode).isEqualTo(200);

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class FormatInt32 {

    String path = "/format/int32";

    @Test
    void formatInt32ShouldReturnBadRequestOnOverflow() {
      try (var server = newServer(Map.of("format-int32", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?n=2147483648", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();
        var contentType = response.headers().firstValue("Content-Type").orElse("");
        var responseBody = response.body();

        assertThat(statusCode).isEqualTo(400);
        assertThat(contentType).contains("application/problem+json");
        assertThat(responseBody).contains("\"format\"");

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void formatInt32ShouldReturnOkOnValidValue() {
      try (var server = newServer(Map.of("format-int32", req -> Response.status(200)));
          var client = httpClient()) {

        var request = newRequest(server, path + "?n=42", "GET", noBody());

        var response = client.send(request, BodyHandlers.ofString());
        var statusCode = response.statusCode();

        assertThat(statusCode).isEqualTo(200);

      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }

  @Nested
  class Gates {

    String path = "/gates";

    @Test
    void postGateBodyWithOnlyOpenReturns200() {
      try (var server = newServer(Map.of("post-gate", new EchoHandler()));
          var client = httpClient()) {
        var body = "{\"open\":\"anything\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }

    @Test
    void postGateBodyWithBlockedReturns400() {
      try (var server = newServer(Map.of("post-gate", new EchoHandler()));
          var client = httpClient()) {
        // Any value in 'blocked' triggers the false-schema rejection,
        // because NeverSchema rejects every value.
        var body = "{\"open\":\"x\",\"blocked\":\"anything\"}";
        var request = newRequest(server, path, "POST", ofString(body));

        var response = client.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/problem+json");
        assertThat(response.body()).contains("\"keyword\":\"false\"");
      } catch (IOException e) {
        fail(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
    }
  }
}
