package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * Parsed JSON Schema {@code type: boolean} node.
 *
 * <p>A boolean schema accepts the JSON literals {@code true} or {@code false}. It carries no
 * additional constraints beyond the type itself, so validation succeeds for any boolean value and
 * fails for any other JSON kind.
 *
 * @param types the declared JSON Schema types for this node. Should be a singleton {@code
 *     [BOOLEAN]}, or include {@link TypeName#NULL} alongside {@code BOOLEAN} when the node is
 *     nullable (e.g. {@code type: [boolean, "null"]}).
 * @param extensions OpenAPI {@code x-} extension keywords declared on this schema node, keyed by
 *     their full extension name (including the {@code x-} prefix). Never {@code null}; empty when
 *     no extensions are present.
 */
public record BooleanSchema(Set<TypeName> types, Map<String, Object> extensions)
    implements Schema {}
