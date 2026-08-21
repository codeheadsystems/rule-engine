package com.codeheadsystems.rules.rule;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A constant value in a {@code then} block (spec §2.5).
 *
 * <p>Deep-copied on construction: a literal is reachable from the shared, immutable compiled rule
 * set, so retaining the caller's node would make that object mutable from outside.
 *
 * @param value the constant
 */
public record Literal(JsonNode value) implements ValueExpr {

  /**
   * Canonical constructor.
   *
   * @param value the constant
   */
  public Literal {
    Objects.requireNonNull(value, "value");
    value = value.deepCopy();
  }
}
