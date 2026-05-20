package com.retailsvc.http;

import java.util.List;
import java.util.Objects;

/**
 * Wire-shape carrier for the {@link Handlers#healthHandler health handler} response.
 *
 * <p>The record owns the JSON shape on the wire — {@code {"outcome": "...", "dependencies": [
 * {"id": "...", "status": "..."} ]}}. Construct it from whatever check-running mechanism the caller
 * prefers; this library has no opinion.
 *
 * @param outcome overall outcome; {@code "Up"} (case-insensitive) means healthy
 * @param dependencies per-dependency statuses; {@code null} is normalised to an empty list
 */
public record HealthOutcome(String outcome, List<Dependency> dependencies) {

  public HealthOutcome {
    Objects.requireNonNull(outcome, "outcome");
    dependencies = List.copyOf(Objects.requireNonNullElse(dependencies, List.of()));
  }

  /** Returns {@code true} when {@link #outcome()} equals {@code "Up"} ignoring case. */
  public boolean isUp() {
    return "Up".equalsIgnoreCase(outcome);
  }
}
