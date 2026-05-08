package com.retailsvc.http.spec.schema;

public sealed interface AdditionalProperties {
  record Allowed() implements AdditionalProperties {}

  record Forbidden() implements AdditionalProperties {}

  record SchemaConstraint(Schema schema) implements AdditionalProperties {}
}
