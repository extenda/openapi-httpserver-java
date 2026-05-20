package com.retailsvc.http.spec.schema;

import java.util.Map;
import java.util.Set;

/**
 * Root of the parsed OpenAPI / JSON Schema AST.
 *
 * <p>This sealed interface has one variant per JSON Schema construct: {@link StringSchema}, {@link
 * NumberSchema}, {@link IntegerSchema}, {@link BooleanSchema}, {@link ObjectSchema}, {@link
 * ArraySchema}, {@link NullSchema}, {@link RefSchema}, {@link OneOfSchema}, {@link AnyOfSchema},
 * {@link AllOfSchema}, {@link NotSchema}, {@link ConstSchema}, {@link EnumSchema}, {@link
 * AlwaysSchema}, and {@link NeverSchema}.
 *
 * <p>Pattern-match against the variants in a {@code switch} to dispatch over the schema kind
 * without resorting to {@code instanceof} chains.
 */
public sealed interface Schema
    permits StringSchema,
        NumberSchema,
        IntegerSchema,
        BooleanSchema,
        ObjectSchema,
        ArraySchema,
        NullSchema,
        RefSchema,
        OneOfSchema,
        AnyOfSchema,
        AllOfSchema,
        NotSchema,
        ConstSchema,
        EnumSchema,
        AlwaysSchema,
        NeverSchema {
  /**
   * The JSON Schema {@code type} set for this node. Empty for combinator schemas ({@code allOf} /
   * {@code anyOf} / {@code oneOf} / {@code not} / {@code const} / {@code enum} / {@code always} /
   * {@code never}) where the type is derived from sub-schemas or is not applicable.
   *
   * @return the declared types
   */
  Set<TypeName> types();

  /**
   * OpenAPI {@code x-}-prefixed extension keywords kept verbatim.
   *
   * @return immutable map of extension name to raw value
   */
  Map<String, Object> extensions();
}
