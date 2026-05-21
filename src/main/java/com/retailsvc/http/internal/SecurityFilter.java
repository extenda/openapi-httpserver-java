package com.retailsvc.http.internal;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

import com.retailsvc.http.Request;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SecurityFilter extends Filter {

  private final Map<String, Operation> operationsById;
  private final Map<String, SecurityScheme> schemes;
  private final List<SecurityRequirement> rootSecurity;
  private final Map<String, SchemeValidator> validators;
  private final boolean externalAuth;

  public SecurityFilter(
      Map<String, Operation> operationsById,
      Map<String, SecurityScheme> schemes,
      List<SecurityRequirement> rootSecurity,
      Map<String, SchemeValidator> validators,
      boolean externalAuth) {
    this.operationsById = Map.copyOf(operationsById);
    this.schemes = Map.copyOf(schemes);
    this.rootSecurity = List.copyOf(rootSecurity);
    this.validators = Map.copyOf(validators);
    this.externalAuth = externalAuth;
  }

  @Override
  public String description() {
    return "Security";
  }

  @Override
  public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
    if (externalAuth) {
      chain.doFilter(exchange);
      return;
    }

    Request request = DispatchHandler.CURRENT.get();
    Operation op = operationsById.get(request.operationId());
    List<SecurityRequirement> effective = op.security().orElse(rootSecurity);

    if (effective.isEmpty()) {
      chain.doFilter(exchange);
      return;
    }

    List<GroupOutcome.Failed> failures = new ArrayList<>();
    for (SecurityRequirement group : effective) {
      GroupOutcome outcome = tryGroup(group, exchange, request);
      if (outcome instanceof GroupOutcome.Allowed(Map<String, Object> principals)) {
        try {
          ScopedValue.where(DispatchHandler.CURRENT, request.withPrincipals(principals))
              .call(
                  () -> {
                    chain.doFilter(exchange);
                    return null;
                  });
        } catch (IOException | RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new IOException(e);
        }
        return;
      }
      failures.add((GroupOutcome.Failed) outcome);
    }

    renderRejection(exchange, failures);
  }

  private GroupOutcome tryGroup(SecurityRequirement group, HttpExchange exchange, Request request) {
    Map<String, Object> principals = new LinkedHashMap<>();
    for (var entry : group.schemes().entrySet()) {
      String schemeName = entry.getKey();
      SecurityScheme scheme = schemes.get(schemeName);
      ExtractionResult result = CredentialExtractor.extract(scheme, exchange);
      if (result.kind() == ExtractionResult.Kind.MISSING) {
        return new GroupOutcome.Failed(FailureKind.MISSING, schemeName);
      }
      if (result.kind() == ExtractionResult.Kind.MALFORMED) {
        return new GroupOutcome.Failed(FailureKind.MALFORMED, schemeName);
      }
      Optional<Object> principal =
          validators.get(schemeName).validate(request, result.credential());
      if (principal.isEmpty()) {
        return new GroupOutcome.Failed(FailureKind.DENIED, schemeName);
      }
      principals.put(schemeName, principal.get());
    }
    return new GroupOutcome.Allowed(Map.copyOf(principals));
  }

  private void renderRejection(HttpExchange exchange, List<GroupOutcome.Failed> failures)
      throws IOException {
    boolean anyDenied = failures.stream().anyMatch(f -> f.kind() == FailureKind.DENIED);
    int status = anyDenied ? HTTP_FORBIDDEN : HTTP_UNAUTHORIZED;
    String title = anyDenied ? "Forbidden" : "Unauthorized";

    GroupOutcome.Failed pick =
        failures.stream().max(Comparator.comparing(GroupOutcome.Failed::kind)).orElseThrow();
    String detail = describe(pick);

    ProblemDetail problemDetail =
        new ProblemDetail("about:blank", title, status, detail, null, null);
    byte[] body = ProblemDetailRenderer.renderJson(problemDetail);
    exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
    if (!anyDenied) {
      LinkedHashSet<String> attempted = new LinkedHashSet<>();
      for (GroupOutcome.Failed f : failures) {
        attempted.add(f.schemeName());
      }
      for (String name : attempted) {
        exchange.getResponseHeaders().add("WWW-Authenticate", challengeFor(name));
      }
    }
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private String describe(GroupOutcome.Failed f) {
    return switch (f.kind()) {
      case MISSING -> "credential missing for scheme '" + f.schemeName() + "'";
      case MALFORMED -> "credential malformed for scheme '" + f.schemeName() + "'";
      case DENIED -> "validator denied for scheme '" + f.schemeName() + "'";
    };
  }

  private String challengeFor(String schemeName) {
    SecurityScheme scheme = schemes.get(schemeName);
    return switch (scheme) {
      case SecurityScheme.HttpBearer _ -> "Bearer realm=\"api\"";
      case SecurityScheme.HttpBasic _ -> "Basic realm=\"api\"";
      case SecurityScheme.ApiKey(String name, SecurityScheme.ApiKey.Location location) ->
          "ApiKey location=" + location.name().toLowerCase(Locale.ROOT) + ", name=\"" + name + "\"";
      case SecurityScheme.Unsupported _ ->
          throw new IllegalStateException(
              "Unsupported scheme reached challenge rendering for '" + schemeName + "'");
    };
  }

  private sealed interface GroupOutcome permits GroupOutcome.Allowed, GroupOutcome.Failed {

    record Allowed(Map<String, Object> principals) implements GroupOutcome {}

    record Failed(FailureKind kind, String schemeName) implements GroupOutcome {}
  }

  private enum FailureKind {
    MISSING,
    MALFORMED,
    DENIED
  }
}
