package com.retailsvc.http.internal;

import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.spec.Operation;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves an OpenAPI {@link Operation} from an HTTP method and request path. */
public final class Router {

  /**
   * Successful route match.
   *
   * @param operation the matched OpenAPI operation
   * @param pathParameters extracted path-template variables (empty for exact matches)
   */
  public record Match(Operation operation, Map<String, String> pathParameters) {}

  private final Map<HttpMethod, Map<String, Operation>> exact = new EnumMap<>(HttpMethod.class);
  private final Map<HttpMethod, List<Operation>> templated = new EnumMap<>(HttpMethod.class);

  /**
   * Indexes operations by method, splitting exact paths from templated paths for fast lookup.
   *
   * @param operations the operations declared in the OpenAPI spec
   */
  public Router(List<Operation> operations) {
    for (HttpMethod m : HttpMethod.values()) {
      exact.put(m, new LinkedHashMap<>());
      templated.put(m, new ArrayList<>());
    }
    for (Operation op : operations) {
      if (op.path().parameterNames().isEmpty()) {
        exact.get(op.method()).put(op.path().raw(), op);
      } else {
        templated.get(op.method()).add(op);
      }
    }
  }

  /**
   * Finds the operation matching the given method and path.
   *
   * @param method the HTTP method
   * @param path the request path with the base path stripped
   * @return the matching operation and any extracted path parameters, or empty if none matches
   */
  public Optional<Match> match(HttpMethod method, String path) {
    Operation hit = exact.get(method).get(path);
    if (hit != null) {
      return Optional.of(new Match(hit, Map.of()));
    }
    for (Operation op : templated.get(method)) {
      Optional<Map<String, String>> params = op.path().match(path);
      if (params.isPresent()) {
        return Optional.of(new Match(op, params.get()));
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the set of HTTP methods that have an operation for the given path. Used to build the
   * {@code Allow} header on 405 responses.
   *
   * @param path the request path with the base path stripped
   * @return the methods declared for that path; empty if the path is unknown
   */
  public Set<HttpMethod> allowedMethods(String path) {
    EnumSet<HttpMethod> out = EnumSet.noneOf(HttpMethod.class);
    for (HttpMethod m : HttpMethod.values()) {
      if (exact.get(m).containsKey(path)) {
        out.add(m);
        continue;
      }
      for (Operation op : templated.get(m)) {
        if (op.path().match(path).isPresent()) {
          out.add(m);
          break;
        }
      }
    }
    return out;
  }
}
