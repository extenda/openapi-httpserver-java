package com.retailsvc.http.internal;

import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coerces string-typed values produced by {@link FormUrlEncodedParser} into the Java types
 * described by the body schema (numbers, booleans, arrays). Called by {@link
 * RequestPreparationFilter} after parsing, before validation.
 */
final class FormBodyCoercion {

  private FormBodyCoercion() {}

  static Map<String, Object> coerce(Map<String, Object> parsed, Schema schema) {
    if (!(schema instanceof ObjectSchema obj)) {
      return parsed;
    }
    Map<String, Schema> properties = obj.properties();
    for (Map.Entry<String, Object> e : parsed.entrySet()) {
      Schema propSchema = properties.get(e.getKey());
      if (propSchema == null) {
        continue;
      }
      String pointer = "/" + e.getKey();
      Object value = e.getValue();
      if (propSchema instanceof ArraySchema arr && value instanceof List<?> list) {
        List<Object> coerced = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          coerced.add(ValueCoercion.coerce((String) list.get(i), arr.items(), pointer + "/" + i));
        }
        e.setValue(coerced);
      } else if (propSchema instanceof ArraySchema arr && value instanceof String s) {
        e.setValue(List.of(ValueCoercion.coerce(s, arr.items(), pointer + "/0")));
      } else if (value instanceof String s) {
        e.setValue(ValueCoercion.coerce(s, propSchema, pointer));
      }
    }
    return parsed;
  }
}
