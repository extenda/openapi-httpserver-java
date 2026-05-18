package com.retailsvc.http.internal;

import com.retailsvc.http.Request;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Operation;
import com.retailsvc.http.spec.security.SecurityRequirement;
import com.retailsvc.http.spec.security.SecurityScheme;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
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

    for (SecurityRequirement group : effective) {
      Map<String, Object> principals = trySatisfy(group, exchange, request);
      if (principals != null) {
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
    }

    // Rejection rendering is Task 9.
    throw new UnsupportedOperationException("rejection path not implemented yet");
  }

  private Map<String, Object> trySatisfy(
      SecurityRequirement group, HttpExchange exchange, Request request) {
    Map<String, Object> principals = new LinkedHashMap<>();
    for (var entry : group.schemes().entrySet()) {
      String schemeName = entry.getKey();
      SecurityScheme scheme = schemes.get(schemeName);
      ExtractionResult result = CredentialExtractor.extract(scheme, exchange);
      if (result.kind() != ExtractionResult.Kind.FOUND) {
        return null;
      }
      Optional<Object> principal =
          validators.get(schemeName).validate(request, result.credential());
      if (principal.isEmpty()) {
        return null;
      }
      principals.put(schemeName, principal.get());
    }
    return Map.copyOf(principals);
  }
}
