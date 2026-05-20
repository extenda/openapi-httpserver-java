package com.retailsvc.http;

import java.util.List;
import java.util.Objects;

/**
 * Carrier for the {@link Handlers#healthHandler health handler} response.
 *
 * <p>Overall health is derived from {@link #dependencies()}: an empty list reports as {@code "Up"};
 * otherwise the outcome is {@code "Up"} only when every dependency is up. Callers describe their
 * dependencies and the library aggregates.
 *
 * @param dependencies per-dependency statuses; {@code null} is normalised to an empty list
 */
public record HealthOutcome(List<Dependency> dependencies) {

  /** Canonical constructor; normalises a {@code null} dependency list to an empty list. */
  public HealthOutcome {
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }

  /**
   * Reports the aggregate health derived from {@link #dependencies()}.
   *
   * @return {@code true} when every dependency is up (vacuously true for an empty list)
   */
  public boolean up() {
    return dependencies.stream().allMatch(Dependency::up);
  }
}
