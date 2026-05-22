package com.retailsvc.http.internal;

import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.SchemeValidator;
import com.retailsvc.http.spec.Spec;
import com.retailsvc.http.validate.DefaultValidator;
import java.util.Map;

/**
 * Bundles everything the server needs to serve one OpenAPI spec: the spec itself, the handler map
 * keyed by {@code operationId}, the security-scheme validator map, and the derived {@link Router}
 * and {@link DefaultValidator}. One {@code SpecBinding} drives exactly one {@code HttpContext}.
 */
public record SpecBinding(
    Spec spec,
    Map<String, RequestHandler> handlers,
    Map<String, SchemeValidator> securityValidators,
    DefaultValidator validator,
    Router router) {

  public SpecBinding {
    handlers = Map.copyOf(handlers);
    securityValidators = Map.copyOf(securityValidators);
  }

  /** Builds a binding by deriving the {@link Router} and {@link DefaultValidator} from the spec. */
  public static SpecBinding of(
      Spec spec,
      Map<String, RequestHandler> handlers,
      Map<String, SchemeValidator> securityValidators) {
    return new SpecBinding(
        spec,
        handlers,
        securityValidators,
        new DefaultValidator(spec::resolveSchema),
        new Router(spec.operations()));
  }
}
