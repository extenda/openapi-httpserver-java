package com.retailsvc.http.openapi.model;

import java.util.Map;

//

/**
 * The 'components' holds a set of reusable objects for different aspects of the OAS. All objects
 * defined within the Components Object will have no effect on the API unless they are explicitly
 * referenced from outside the Components Object.
 *
 * @see <a href="https://swagger.io/specification/#components-object">Parameter Object</a>
 */
public record Components(Map<String, Schema> schemas, Map<String, Parameter> parameters) {

  public Schema getSchema(String name) {
    return schemas.get(name);
  }

  public Parameter getParameter(String name) {
    return parameters.get(name);
  }
}
