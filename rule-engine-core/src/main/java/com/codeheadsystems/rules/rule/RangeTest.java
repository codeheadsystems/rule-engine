package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import com.codeheadsystems.rules.value.Comparisons;
import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A compiled {@link RangeConstraint}.
 *
 * @param source the constraint this was compiled from
 * @param accessor the precompiled accessor for the constraint's field
 */
public record RangeTest(RangeConstraint source, FieldAccessor accessor) implements AlphaTest {

  /**
   * Canonical constructor.
   *
   * @param source the constraint
   * @param accessor the precompiled accessor
   */
  public RangeTest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(accessor, "accessor");
  }

  @Override
  public Constraint constraint() {
    return source;
  }

  @Override
  public boolean test(final JsonNode payload) {
    return Comparisons.inRange(
        accessor.get(payload),
        source.lower(), source.lowerInclusive(),
        source.upper(), source.upperInclusive());
  }
}
