package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import java.util.Objects;
import java.util.Optional;

/**
 * One {@code ACCUMULATE} pattern, compiled (spec §2.5's second amendment).
 *
 * <p><strong>The scope is a {@link CompiledPattern} and every one of its tests filters.</strong>
 * That is the difference from {@link Quantifier#FOR_ALL}, which reads its two halves differently
 * because it has a requirement to assert about each fact in scope. An accumulate has no such half:
 * there is nothing to be true of a contributing fact beyond its being one, so joins and literals
 * alike select what contributes, and "sum the qty of the <em>physical</em> line items of this order"
 * is expressible where the universal equivalent is not.
 *
 * <p><strong>It contributes no tuple position.</strong> The alias names a value, and a value cannot
 * sit where §3.2.2 requires a handle -- so nothing else in the rule may join to it, and the compiler
 * says so by name. What may read it is everything resolved at fire time rather than at match time:
 * {@link Accumulate#having}, a {@code $ref} in an action, and a §6.4 expression.
 *
 * @param alias the name the answer binds to; binds a value, never a fact
 * @param scope the pattern selecting what contributes, whose join tests point at positions in the
 *     rule's positive patterns
 * @param function what to compute
 * @param field the compiled accessor for the folded field, absent for
 *     {@link AggregateFunction#COUNT}
 * @param having the optional test the answer must pass
 */
public record CompiledAccumulate(String alias, CompiledPattern scope, AggregateFunction function,
    Optional<FieldAccessor> field, Optional<AggregateTest> having) {

  /**
   * Canonical constructor.
   *
   * @param alias the binding name
   * @param scope the selecting pattern
   * @param function what to compute
   * @param field the folded field's accessor
   * @param having the optional test on the answer
   */
  public CompiledAccumulate {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(function, "function");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(having, "having");
  }
}
