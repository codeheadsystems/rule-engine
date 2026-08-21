package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import com.codeheadsystems.rules.value.Comparisons;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A compiled cross-fact test (spec §3.2.1's {@code JoinNode.postFilter}, without the network).
 *
 * <p>{@code otherIndex} is resolved at compile time to the other alias's position in the tuple, so
 * evaluating a join is two accessor reads and a comparison -- no alias-name lookup while matching.
 * §6.5 guarantees the referenced alias is bound <em>earlier</em> in the pattern order, so the
 * position is always already filled when this test runs.
 *
 * @param source the constraint this was compiled from
 * @param accessor the accessor for the field on this pattern's fact
 * @param otherIndex the position of the referenced alias in the rule's pattern order
 * @param otherAccessor the accessor for the field on the referenced alias's fact
 */
public record JoinTest(
    JoinConstraint source, FieldAccessor accessor, int otherIndex, FieldAccessor otherAccessor) {

  /**
   * Canonical constructor.
   *
   * @param source the constraint
   * @param accessor the accessor for this pattern's field
   * @param otherIndex the referenced alias's position
   * @param otherAccessor the accessor for the referenced alias's field
   */
  public JoinTest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(accessor, "accessor");
    Objects.requireNonNull(otherAccessor, "otherAccessor");
    if (otherIndex < 0) {
      throw new IllegalArgumentException("otherIndex must be a resolved pattern position");
    }
  }

  /**
   * Evaluates the join between a candidate fact and an already-bound one.
   *
   * @param payload the candidate fact's payload
   * @param otherPayload the payload of the fact bound at {@link #otherIndex}
   * @return whether the join holds
   */
  public boolean test(final JsonNode payload, final JsonNode otherPayload) {
    return Comparisons.test(
        source.op(), accessor.get(payload), otherAccessor.get(otherPayload));
  }
}
