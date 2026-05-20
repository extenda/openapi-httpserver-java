package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code type: integer}.
 *
 * @param types the declared types (may include {@link TypeName#NULL} for nullable)
 * @param minimum inclusive lower bound, or {@code null}
 * @param maximum inclusive upper bound, or {@code null}
 * @param exclusiveMinimum exclusive lower bound, or {@code null}
 * @param exclusiveMaximum exclusive upper bound, or {@code null}
 * @param multipleOf required divisor, or {@code null}
 * @param format optional format hint (e.g. {@code int32}, {@code int64})
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record IntegerSchema(
    Set<TypeName> types,
    Long minimum,
    Long maximum,
    Long exclusiveMinimum,
    Long exclusiveMaximum,
    Long multipleOf,
    String format,
    Map<String, Object> extensions)
    implements Schema {}
