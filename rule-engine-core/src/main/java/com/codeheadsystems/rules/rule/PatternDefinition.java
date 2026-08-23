package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;

/**
 * One fact-matching clause of an LHS, bound to an alias (spec §2.5).
 *
 * @param alias the binding name, e.g. {@code o}
 * @param factType the fact type this pattern matches, e.g. {@code Order}
 * @param quantifier {@link Quantifier#EXISTS_AT_LEAST_ONE}, {@link Quantifier#NOT_EXISTS} or
 *     {@link Quantifier#FOR_ALL}; {@link Quantifier#ACCUMULATE} is reserved and the compiler
 *     rejects it (§1). The last two bind no fact -- see their own documentation for what that
 *     costs the rest of the rule
 * @param constraints the conditions, in declaration order. Implicitly AND-ed
 */
public record PatternDefinition(
    String alias, String factType, Quantifier quantifier, List<Constraint> constraints) {

  /**
   * Canonical constructor. Defensively copies {@code constraints}.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param quantifier the quantifier
   * @param constraints the conditions
   */
  public PatternDefinition {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(quantifier, "quantifier");
    constraints = List.copyOf(constraints);
  }

  /**
   * Builds an ordinary positive pattern.
   *
   * <p>There is no matching factory for a negated one on purpose: {@link Quantifier#NOT_EXISTS}
   * carries obligations an author has to read (it binds nothing, and §4.4 eviction over its type
   * produces false conclusions), so it is written out in full at the one place that builds it.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param constraints the conditions
   * @return the pattern
   */
  public static PatternDefinition of(
      final String alias, final String factType, final List<Constraint> constraints) {
    return new PatternDefinition(alias, factType, Quantifier.EXISTS_AT_LEAST_ONE, constraints);
  }
}
