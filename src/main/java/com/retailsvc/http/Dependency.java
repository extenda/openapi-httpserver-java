package com.retailsvc.http;

import java.util.Objects;

/**
 * A single dependency entry within a {@link HealthOutcome}.
 *
 * <p>The library translates {@code up} into the wire value {@code "Up"} or {@code "Down"} for the
 * {@code status} field.
 *
 * @param id stable identifier of the dependency (e.g. {@code "jdbc"})
 * @param up whether the dependency is healthy
 */
public record Dependency(String id, boolean up) {
  public Dependency {
    Objects.requireNonNull(id, "id");
  }
}
