package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.value.Comparisons;
import java.util.Objects;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * A compiled cross-fact test (spec §3.2.1's {@code JoinNode.postFilter}, without the network).
 *
 * <p>{@code otherIndex} is resolved at compile time to the other alias's position in the tuple, so
 * evaluating a join is two accessor reads and a comparison -- no alias-name lookup while matching.
 * §6.5 guarantees the referenced alias is bound <em>earlier</em> in the pattern order, so the
 * position is always already filled when this test runs.
 *
 * <p>The accessors are typed as pointer accessors rather than as the general interface so that the
 * indexed path is reachable without re-parsing a path string. §10 forbids path parsing in the hot
 * path, and a matcher that had to recover the path in order to decide whether it is indexed would
 * be doing exactly that.
 *
 * @param source the constraint this was compiled from
 * @param accessor the accessor for the field on this pattern's fact
 * @param otherIndex the position of the referenced alias in the rule's pattern order
 * @param otherAccessor the accessor for the field on the referenced alias's fact
 */
public record JoinTest(
    JoinConstraint source, JsonPointerAccessor accessor, int otherIndex,
    JsonPointerAccessor otherAccessor) {

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
   * The path this join reads on its own pattern's fact -- the one an index would be built on.
   *
   * @return the compiled path
   */
  public JsonPointer path() {
    return accessor.pointer();
  }

  /**
   * The value this join requires, read from the already-bound fact on the other side.
   *
   * @param otherPayload the payload of the fact bound at {@link #otherIndex()}
   * @return the value to probe for
   */
  public JsonNode probeValue(final JsonNode otherPayload) {
    return otherAccessor.get(otherPayload);
  }

  /**
   * Evaluates the join between a candidate fact and an already-bound one.
   *
   * @param payload the candidate fact's payload
   * @param otherPayload the payload of the fact bound at {@link #otherIndex()}
   * @return whether the join holds
   */
  public boolean test(final JsonNode payload, final JsonNode otherPayload) {
    return Comparisons.test(
        source.op(), accessor.get(payload), otherAccessor.get(otherPayload));
  }
}
