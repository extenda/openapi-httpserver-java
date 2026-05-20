package com.retailsvc.http;

import java.util.Objects;

/**
 * A single dependency entry within a {@link HealthOutcome}.
 *
 * @param id stable identifier of the dependency (e.g. {@code "jdbc"})
 * @param status free-form status; {@code "Up"} (case-insensitive) is treated as healthy by {@link
 *     HealthOutcome#isUp()}; any other value is treated as unhealthy
 */
public record Dependency(String id, String status) {
  public Dependency {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(status, "status");
  }
}
