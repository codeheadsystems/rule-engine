package com.codeheadsystems.rules.rule;

import java.util.Objects;
import java.util.Optional;

/**
 * What an {@code ACCUMULATE} pattern computes, and what it requires of the answer (spec §2.5's
 * second amendment).
 *
 * <p>The pattern's own constraints choose the <em>scope</em> -- all of them, joins and literals
 * alike, which is where this differs from {@link Quantifier#FOR_ALL}. A universal splits them
 * because it has a requirement to state about each fact; an accumulate has no such half, so every
 * constraint is a filter and "sum the qty of the PHYSICAL line items of this order" is expressible
 * where the universal equivalent is not.
 *
 * @param function what to compute
 * @param field the dotted path to fold over, absent for {@link AggregateFunction#COUNT}, which
 *     reads no field. The compiler requires it for every other function and refuses it for that one
 * @param having an optional test the answer must pass for the rule to match, evaluated where §1's
 *     negation and §2.5's universal are -- against a complete tuple, in the shared agenda base.
 *     Absent means the accumulate only <em>binds</em>: the rule matches whatever the answer is, and
 *     the right-hand side reads it
 */
public record Accumulate(AggregateFunction function, Optional<String> field,
    Optional<AggregateTest> having) {

  /**
   * Canonical constructor.
   *
   * @param function what to compute
   * @param field the field to fold over, absent for {@code COUNT}
   * @param having the optional test on the answer
   */
  public Accumulate {
    Objects.requireNonNull(function, "function");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(having, "having");
  }
}
