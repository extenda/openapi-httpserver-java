package com.retailsvc.http.internal;

import com.retailsvc.http.Request;
import java.util.Map;

final class ScopedValueHarness {
  private static Map<String, Object> lastSeenPrincipals = Map.of();

  static void runWith(Request seed, ThrowingRunnable r) throws Exception {
    ScopedValue.where(DispatchHandler.CURRENT, seed)
        .call(
            () -> {
              try {
                r.run();
              } finally {
                lastSeenPrincipals = DispatchHandler.CURRENT.get().principals();
              }
              return null;
            });
  }

  static Map<String, Object> lastSeenPrincipals() {
    return lastSeenPrincipals;
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }
}
