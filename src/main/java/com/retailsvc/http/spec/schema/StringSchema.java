package com.retailsvc.http.spec.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema {@code type: string}.
 *
 * @param types the declared types (may include {@link TypeName#NULL} for nullable)
 * @param pattern regex the value must match, or {@code null}
 * @param minLength minimum length, or {@code null}
 * @param maxLength maximum length, or {@code null}
 * @param format optional format hint (e.g. {@code date-time}, {@code uuid})
 * @param enumValues allowed values, or {@code null} if unconstrained
 * @param extensions vendor extensions ({@code x-*} keys)
 */
public record StringSchema(
    Set<TypeName> types,
    String pattern,
    Integer minLength,
    Integer maxLength,
    String format,
    List<String> enumValues,
    Map<String, Object> extensions)
    implements Schema {}
