package com.codeheadsystems.rules.rule;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * A condition on what an {@code ACCUMULATE} answered (spec §2.5's second amendment).
 *
 * <p>Its own record rather than a {@link FieldConstraint} with the field left blank, because the
 * subject is not a field: there is no fact to read it from, and a constraint whose {@code field}
 * meant nothing would be the first in the engine that could not be reported as
 * {@code alias.field} by §7.2's explainer or by the located diagnostics in {@code -dsl}.
 *
 * <p>One operator and one literal, deliberately narrower than a pattern's {@code where}. A range is
 * two of these and is left out until something asks for it; the operators that need a second fact
 * ({@code $ref}) cannot apply, because nothing else in the rule may join to an accumulate alias.
 *
 * @param op how to compare, from the same set §2.6.1 defines for a field
 * @param literal what to compare the answer against. Deep-copied on the way in, like every other
 *     literal in a compiled rule set (§5.5's invariant 1)
 */
public record AggregateTest(Operator op, JsonNode literal) {

  /**
   * Canonical constructor. Deep-copies the literal so the shared rule set holds no caller's node.
   *
   * @param op the comparison
   * @param literal the value to compare against
   */
  public AggregateTest {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(literal, "literal");
    literal = literal.deepCopy();
  }
}
