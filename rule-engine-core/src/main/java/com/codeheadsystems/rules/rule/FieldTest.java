package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import com.codeheadsystems.rules.value.Comparisons;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * A compiled {@link FieldConstraint}, for every operator except {@link Operator#MATCHES}
 * (which is {@link RegexTest}).
 *
 * @param source the constraint this was compiled from
 * @param accessor the precompiled accessor for the constraint's field
 */
public record FieldTest(FieldConstraint source, FieldAccessor accessor) implements AlphaTest {

  /**
   * Canonical constructor.
   *
   * @param source the constraint
   * @param accessor the precompiled accessor
   */
  public FieldTest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(accessor, "accessor");
    if (source.op() == Operator.MATCHES) {
      throw new IllegalArgumentException("MATCHES compiles to a RegexTest, not a FieldTest");
    }
  }

  @Override
  public Constraint constraint() {
    return source;
  }

  @Override
  public boolean test(final JsonNode payload) {
    return Comparisons.test(source.op(), accessor.get(payload), source.literal());
  }
}
