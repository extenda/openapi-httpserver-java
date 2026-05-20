package com.retailsvc.http.spec.schema;

/**
 * Models the OpenAPI / JSON Schema {@code additionalProperties} keyword, which controls whether
 * properties not listed in an object schema's {@code properties} map are permitted, and if so,
 * whether they must conform to a constraint schema.
 *
 * <p>This sealed interface has three variants that map directly to the three forms the keyword can
 * take in a schema document:
 *
 * <ul>
 *   <li>{@link Allowed} — {@code additionalProperties: true} or omitted entirely.
 *   <li>{@link Forbidden} — {@code additionalProperties: false}.
 *   <li>{@link SchemaConstraint} — {@code additionalProperties: { ...schema... }}.
 * </ul>
 *
 * @see <a
 *     href="https://json-schema.org/draft/2020-12/json-schema-core#name-additionalproperties">JSON
 *     Schema 2020-12 — additionalProperties</a>
 */
public sealed interface AdditionalProperties {
  /**
   * Matches {@code additionalProperties: true} or the absence of the keyword (the JSON Schema
   * default). Any extra property of any type is accepted during validation without further
   * constraint.
   */
  record Allowed() implements AdditionalProperties {}

  /**
   * Matches {@code additionalProperties: false}. Any property present on an instance that is not
   * declared in the schema's {@code properties} map causes validation to fail.
   */
  record Forbidden() implements AdditionalProperties {}

  /**
   * Matches {@code additionalProperties: {schema}}. Extra properties not declared in {@code
   * properties} are permitted, but each such property's value must validate against the supplied
   * constraint schema.
   *
   * @param schema the schema that every additional (undeclared) property value must satisfy
   */
  record SchemaConstraint(Schema schema) implements AdditionalProperties {}
}
