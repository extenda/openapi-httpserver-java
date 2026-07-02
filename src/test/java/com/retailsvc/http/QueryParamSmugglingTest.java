package com.retailsvc.http;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.retailsvc.http.spec.Spec;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryParamSmugglingTest extends ServerBaseTest {

  @Test
  void encodedAmpersandCannotSmuggleAValuePastQueryValidation() throws Exception {
    // Constrain q1 so any value containing '&' or '=' fails validation.
    constrainQ1ToLowercaseLetters();
    server = newServer(Map.of());

    // %26 must stay inside q1: the handler would decode q1 to "abc&x=y", so validation must see the
    // same value and reject it against ^[a-z]+$ (400). The exploit was that validation parsed the
    // already-decoded query and saw q1="abc" (passing) while the handler received "abc&x=y".
    var request = newRequest(server, "/params/query?q1=abc%26x=y&q2=ok", "GET", noBody());
    HttpResponse<String> response = httpClient().send(request, BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @SuppressWarnings("unchecked")
  private void constrainQ1ToLowercaseLetters() {
    spec =
        assertDoesNotThrow(
            () -> {
              try (InputStream in =
                  QueryParamSmugglingTest.class.getResourceAsStream("/openapi.json")) {
                Map<String, Object> raw =
                    (Map<String, Object>)
                        gson.fromJson(
                            new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
                var paths = (Map<String, Object>) raw.get("paths");
                var op =
                    (Map<String, Object>)
                        ((Map<String, Object>) paths.get("/params/query")).get("get");
                for (Object p : (List<Object>) op.get("parameters")) {
                  var param = (Map<String, Object>) p;
                  if ("q1".equals(param.get("name"))) {
                    ((Map<String, Object>) param.get("schema")).put("pattern", "^[a-z]+$");
                  }
                }
                return Spec.from(raw);
              }
            });
  }
}
