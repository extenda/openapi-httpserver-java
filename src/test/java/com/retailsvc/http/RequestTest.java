package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.retailsvc.http.internal.LegacyRequestAccess;
import com.retailsvc.http.internal.RequestContext;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestTest {

  @Test
  void readsBoundContext() throws Exception {
    RequestContext ctx =
        new RequestContext(new byte[] {1, 2, 3}, Map.of("k", "v"), "get-x", Map.of("id", "42"));

    AtomicReference<byte[]> seenBytes = new AtomicReference<>();
    AtomicReference<Object> seenParsed = new AtomicReference<>();
    AtomicReference<String> seenOpId = new AtomicReference<>();
    AtomicReference<Map<String, String>> seenPathParams = new AtomicReference<>();

    ScopedValue.where(LegacyRequestAccess.CONTEXT, ctx)
        .call(
            () -> {
              seenBytes.set(LegacyRequestAccess.bytes());
              seenParsed.set(LegacyRequestAccess.parsed());
              seenOpId.set(LegacyRequestAccess.operationId());
              seenPathParams.set(LegacyRequestAccess.pathParams());
              return null;
            });

    assertThat(seenBytes.get()).containsExactly(1, 2, 3);
    assertThat(seenParsed.get()).isEqualTo(Map.of("k", "v"));
    assertThat(seenOpId.get()).isEqualTo("get-x");
    assertThat(seenPathParams.get()).containsEntry("id", "42");
  }

  @Test
  void readingOutsideScopeThrows() {
    assertThrows(NoSuchElementException.class, LegacyRequestAccess::bytes);
  }
}
