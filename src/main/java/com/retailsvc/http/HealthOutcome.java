package com.retailsvc.http;

import java.util.List;
import java.util.Objects;

/**
 * Carrier for the {@link Handlers#healthHandler health handler} response.
 *
 * <p>The library translates {@code up} into the wire value {@code "Up"} or {@code "Down"} on the
 * way out; callers work in booleans. Construct the outcome from whatever check-running mechanism
 * the caller prefers — this library has no opinion.
 *
 * @param up overall health — {@code true} renders as {@code "Up"} with HTTP 200; {@code false}
 *     renders as {@code "Down"} with HTTP 503
 * @param dependencies per-dependency statuses; {@code null} is normalised to an empty list
 */
public record HealthOutcome(boolean up, List<Dependency> dependencies) {

  public HealthOutcome {
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }
}
